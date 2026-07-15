import requests
import time
import json
import hashlib
import base64
import random
import urllib.parse
import re
import urllib3

# 屏蔽不安全HTTPS警告
urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

# ===================== 全局常量 =====================
vdFileHost = "https://vd-file.wojiazongguan.cn"
productKey = "3bd0c1bc-f50"
signSecret = "html5_open_api_check_secret"
channelName = "720p"

# ===================== 加密解密工具（完全复刻ltcrypto.go） =====================
def jsEscape(s: str) -> str:
    """模拟JS escape()"""
    sb = []
    for r in s:
        if (r.isascii() and r.isalnum()) or r in "*@-_.+/":
            sb.append(r)
        elif ord(r) <= 0xFF:
            sb.append(f"%{ord(r):02X}")
        else:
            sb.append(f"%u{ord(r):04X}")
    return "".join(sb)

def jsUnescape(s: str) -> str:
    """模拟JS unescape()"""
    # 先处理 %uXXXX
    pattern_u = re.compile(r"%u([0-9A-Fa-f]{4})")
    def replace_u(match):
        code = int(match.group(1), 16)
        return chr(code)
    res = pattern_u.sub(replace_u, s)

    # 处理 %XX
    sb = []
    i = 0
    n = len(res)
    while i < n:
        if res[i] == "%" and i + 2 < n:
            try:
                hex_str = res[i+1:i+3]
                b = int(hex_str, 16)
                sb.append(chr(b))
                i += 3
                continue
            except ValueError:
                pass
        sb.append(res[i])
        i += 1
    return "".join(sb)

def EncryptParam(plaintext: str) -> str:
    """加密 _paramStr_"""
    runes = list(plaintext)
    half = len(runes) // 2
    swapped = "".join(runes[half:]) + "".join(runes[:half])
    escaped = jsEscape(swapped)
    b64 = base64.b64encode(escaped.encode("utf-8")).decode("utf-8")
    # 生成10位随机数字，转base64取前10位作为前缀
    rand_num = random.randint(0, 8999999999) + 1000000000
    prefix_raw = f"{rand_num:010d}".encode("utf-8")
    prefix = base64.b64encode(prefix_raw).decode("utf-8")[:10]
    return prefix + b64

def DecryptParam(encrypted: str) -> str:
    """解密 _paramStr_"""
    if not encrypted:
        return ""
    without_prefix = encrypted[10:]
    try:
        decoded_bytes = base64.b64decode(without_prefix)
    except Exception:
        return ""
    url_decoded = jsUnescape(decoded_bytes.decode("utf-8"))
    runes = list(url_decoded)
    l = len(runes)
    half = l // 2
    return "".join(runes[l-half:]) + "".join(runes[:l-half])

# ===================== 通用工具函数 =====================
def Md5Sum(s: str) -> str:
    h = hashlib.md5(s.encode("utf-8"))
    return h.hexdigest()

def RandomDigits(n: int) -> str:
    return "".join(str(random.randint(0, 9)) for _ in range(n))

def vdStr(m: dict, key: str) -> str:
    v = m.get(key, "")
    return str(v)

def FmtPrint(fmt: str, *args):
    print(fmt % args)

def vdSign(payload: dict) -> str:
    tmp = []
    for k in payload:
        if k != "signature":
            tmp.append(k)
    tmp.sort()
    sb = signSecret
    for k in tmp:
        v = payload[k]
        sb += f"{k}={v}"
    return Md5Sum(sb)

# ===================== 通用请求封装 =====================
def httpPost(urlStr: str, body: dict) -> dict:
    headers = {
        "Content-Type": "application/x-www-form-urlencoded",
        "User-Agent": "Dalvik/2.1.0 (Linux; U; Android 16; 2211133C Build/BP2A.250605.031.A3);unicom{version:android@12.0900};ltst;"
    }
    form_data = urllib.parse.urlencode(body)
    client = requests.Session()
    client.verify = False
    client.timeout = 15
    last_err = None
    resp = None
    for i in range(3):
        try:
            resp = client.post(urlStr, data=form_data, headers=headers)
            last_err = None
            break
        except Exception as e:
            last_err = e
            if i < 2:
                time.sleep(2)
    if last_err is not None:
        raise Exception(f"httpPost failed: {last_err}")
    return resp.json()

def httpGet(urlStr: str) -> dict:
    headers = {
        "User-Agent": "ChinaUnicom/12.1200 (Android 16)"
    }
    client = requests.Session()
    client.verify = False
    client.timeout = 15
    last_err = None
    resp = None
    for i in range(3):
        try:
            resp = client.get(urlStr, headers=headers)
            last_err = None
            break
        except Exception as e:
            last_err = e
            if i < 2:
                time.sleep(2)
    if last_err is not None:
        raise Exception(f"httpGet failed: {last_err}")
    return resp.json()

def vdPost(apiPath: str, payload: dict) -> dict:
    payload["_timestamp"] = int(time.time() * 1000)
    payload["signature"] = vdSign(payload)
    json_str = json.dumps(payload, ensure_ascii=False)
    paramStr = EncryptParam(json_str)
    body = f"_paramStr_={urllib.parse.quote(paramStr, safe='')}"
    headers = {
        "Content-Type": "application/x-www-form-urlencoded",
        "User-Agent": "ChinaUnicom/12.1200 (Android 16)"
    }
    url = vdFileHost + apiPath
    client = requests.Session()
    client.verify = False
    client.timeout = 15
    resp = client.post(url, data=body, headers=headers)
    plain = DecryptParam(resp.text)
    if not plain.strip():
        raise Exception(f"解密结果为空！原始响应：{repr(resp.text)}")
    return json.loads(plain)

# ===================== 登录链路接口 =====================
def refreshToken(tokenOnline: str) -> tuple[str, str]:
    body = {
        "version": "android@12.0900",
        "token_online": tokenOnline
    }
    resp = httpPost("https://loginxhm.10010.com/mobileService/onLine.htm", body)
    if vdStr(resp, "code") != "0":
        raise Exception(f"refreshToken error resp: {resp}")
    privateToken = vdStr(resp, "private_token")
    mobile = vdStr(resp, "desmobile")
    FmtPrint("登录成功: %s", mobile)
    return privateToken, mobile

def getTicketNative(privateToken: str) -> str:
    appId = "edop_unicom_7da41905"
    tokenEsc = urllib.parse.quote(privateToken)
    url = f"https://m.client.10010.com/edop_ng/getTicketByNative?appId={appId}&token={tokenEsc}"
    resp = httpGet(url)
    ticket = vdStr(resp, "ticket")
    if ticket == "":
        raise Exception(f"getTicketNative empty ticket: {resp}")
    FmtPrint("获取 Ticket: %s", ticket)
    return ticket

def getAutoLoginToken(ticket: str) -> str:
    reqSeq = RandomDigits(5)
    resTime = str(int(time.time() * 1000))
    signRaw = "UnicomAppMiniProgramAutoLogin" + resTime + reqSeq + "wohome"
    sign = Md5Sum(signRaw)
    reqBody = json.dumps({
        "header": {
            "key": "UnicomAppMiniProgramAutoLogin",
            "resTime": resTime,
            "reqSeq": reqSeq,
            "channel": "wohome",
            "version": "",
            "sign": sign
        },
        "body": {
            "ticket": ticket,
            "appId": "edop_unicom_7da41905",
            "clientId": "1001000122"
        }
    }, ensure_ascii=False)
    headers = {
        "Content-Type": "application/json",
        "User-Agent": "Mozilla/5.0 (Linux; Android 16; 2211133C Build/BP2A.250605.031.A3; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/137.0.7151.115 Mobile Safari/537.36; unicom{version:android@12.1200,desmobile:0}"
    }
    client = requests.Session()
    client.verify = False
    client.timeout = 15
    resp = client.post("https://iotpservice.smartont.net/wohome/dispatcher", data=reqBody, headers=headers)
    result = resp.json()
    rsp = result.get("RSP", {})
    data = rsp.get("DATA", {})
    accessToken = vdStr(data, "accessToken")
    if accessToken == "":
        raise Exception(f"getAutoLoginToken empty accessToken: {result}")
    FmtPrint("获取 accessToken: %s", accessToken)
    return accessToken

def cloudLogin(mobile: str, accessToken: str) -> str:
    deviceId = RandomDigits(16)
    extra = json.dumps({
        "accessToken": accessToken,
        "phone": mobile,
        "deviceType": "WEB",
        "deviceId": deviceId,
        "appName": "smartHome",
        "version": "0.0.1"
    }, separators=(",", ":"))
    payload = {
        "productKey": productKey,
        "account": mobile,
        "loginType": "WJJK",
        "extra": extra
    }
    resp = vdPost("/h5player/api/open/cloud/thirdLogin", payload)
    data = resp.get("data", {})
    cloudToken = vdStr(data, "token")
    if cloudToken == "":
        raise Exception(f"cloudLogin empty token: {resp}")
    FmtPrint("获取视频云 Token: %s", cloudToken)
    return cloudToken

# ===================== 设备结构体 & 业务接口 =====================
class DeviceInfo:
    def __init__(self, dev: dict):
        self.DeviceId = vdStr(dev, "deviceid")
        self.DeviceName = vdStr(dev, "devicename")
        self.ChannelNo = vdStr(dev, "channelNo")
        self.Status = vdStr(dev, "onlineStatus")
        rawRegion = vdStr(dev, "region")
        self.Region = rawRegion.removesuffix("/cds")
        self.RelayHost = ""
        self.RelayPort = ""
        iplist = dev.get("iplist", [])
        if len(iplist) > 0 and isinstance(iplist[0], dict):
            ip = iplist[0]
            self.RelayHost = vdStr(ip, "relayhost")
            self.RelayPort = vdStr(ip, "relayport")

def getDeviceList(token: str) -> list[DeviceInfo]:
    payload = {
        "token": token,
        "productKey": productKey,
        "settingCodes": "[501,500,2067,1086,2045]"
    }
    resp = vdPost("/h5player/api/open/esd/deviceList", payload)
    data = resp.get("data", {})
    rawList = data.get("devicelist", [])
    devs = []
    for d in rawList:
        devs.append(DeviceInfo(d))
    return devs

class Video:
    def __init__(self, name, wsHost, devId, chNo, token, relayServer):
        self.Name = name
        self.Size = 10
        self.Count = 10
        self.WsHost = wsHost
        self.DeviceId = devId
        self.ChannelNo = chNo
        self.Token = token
        self.RelayServer = relayServer

# 主流程：刷新登录 → 获取设备列表 → 生成视频配置
def AutoConfig(tokenOnline: str) -> list[Video]:
    FmtPrint("获取账号中的摄像头设备...")
    # 1. 刷新登录
    privateToken, mobile = refreshToken(tokenOnline)
    # 2. 获取票据
    ticket = getTicketNative(privateToken)
    # 3. 获取accessToken
    accessToken = getAutoLoginToken(ticket)
    # 4. 视频云登录
    cloudToken = cloudLogin(mobile, accessToken)
    # 5. 获取设备列表
    devices = getDeviceList(cloudToken)
    if len(devices) == 0:
        FmtPrint("未发现任何设备")
        return []
    # 6. 全局wsHost
    wsHost = ""
    for dev in devices:
        if dev.Status == "available" and dev.Region != "":
            wsHost = dev.Region
            break
    if wsHost == "":
        FmtPrint("无法获取 WebSocket 地址")
        return []
    # 7. 组装视频配置
    videos = []
    for dev in devices:
        if dev.Status != "available":
            FmtPrint("跳过离线设备: %s", dev.DeviceName)
            continue
        if dev.Region == "":
            dev.Region = wsHost
        if dev.RelayHost == "" or dev.RelayPort == "":
            FmtPrint("跳过无中继设备: %s", dev.DeviceName)
            continue
        relayServer = f"{dev.RelayHost}:{dev.RelayPort}"
        videos.append(Video(
            name=dev.DeviceName,
            wsHost=dev.Region,
            devId=dev.DeviceId,
            chNo=dev.ChannelNo,
            token=cloudToken,
            relayServer=relayServer
        ))
    FmtPrint("账号中共有：%d台摄像头设备", len(videos))
    FmtPrint("")
    return videos

# ===================== 测试入口 =====================
if __name__ == "__main__":
    # 替换为你自己的 token_online
    TOKEN_ONLINE = "037cc84a13842bb219e5e77ae5991b65aad126b591eb0e64dee55838722a6eaee8b084693c2edf4e869b04e1e5d4673b08561de2ea908ab82295e2500bbad977c43a50c98557651fdde47a9ea6f4ca2a2f6cb899c503facd7ef4bea31047fed4069c4d0b3ec2274ebadb22a0f4e58356318bb8aa7f32175ed509c0f1256a08ff0f25a86c8e15ac3f5459adf95e64483ca18f0f2b0644796861fbb1fadd6a0f03a29318054f61f264bb3ce4dd75b6f241e291c3bc0b83cf39f5e497ca61a5c5df8cdc49c64e68d7881d31d3a24f8c87b881f70fcd8eccb9c977810afab6849b9f63ab69eb84081d08be082002cd1f9e775cf03814ec04f53eb4f9079c5c13b0fbd3ffdc6fc7ada5cb27d66929a01f369d5a6eecd7110fd67aff065da01399800d51c29504939f068fa75feb76c5358aa1c7ca93fd6104a19d53ca752c86f087bb"
    video_list = AutoConfig(TOKEN_ONLINE)
    # 打印所有设备配置
    for idx, v in enumerate(video_list, 1):
        print(f"===== 设备{idx} =====")
        print(f"设备名称: {v.Name}")
        print(f"WebSocket主机: {v.WsHost}")
        print(f"设备ID: {v.DeviceId}")
        print(f"通道号: {v.ChannelNo}")
        print(f"中继地址: {v.RelayServer}")
        print(f"云Token: {v.Token[:60]}...")
