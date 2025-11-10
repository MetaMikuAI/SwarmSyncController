from pwn import * # pip install pwntools
import time
import hmac
import json
import struct
import hashlib
from typing import Any
from Crypto.Cipher import AES
from cryptography.hazmat.backends import default_backend
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes



# 设备连接信息
DEVICE_IP = '192.168.43.187'
DEVICE_PORT = 6668
HMAC_KEY = '****************'

# 消息格式常量
PREFIX_6699_VALUE = 0x00006699
PREFIX_6699_BIN = b"\x00\x00\x66\x99"
SUFFIX_6699_BIN = b"\x00\x00\x99\x66"
MESSAGE_HEADER_FMT_6699 = ">IHIII"  # prefix, unknown, seqno, cmd, length
MESSAGE_END_FMT_6699 = ">16sI"     # tag, suffix

# 命令类型
SESS_KEY_NEG_START = 3
SESS_KEY_NEG_RESP = 4
SESS_KEY_NEG_FINISH = 5
DP_QUERY_NEW = 0x10  # 16
CONTROL_NEW = 13
header_version = b'3.5\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00'

REPEAT = 1

class NeuroLamp:
    def __init__(self, local_key: str, device_ip: str, device_port: int):
        self.seqno = 1
        self.hmac_key = local_key.encode('utf8')
        self.device_ip = device_ip
        self.device_port = device_port
        self.sock = None
        self.session_key = None
        self.connect()
    
    def connect(self):
        context(log_level = 'debug') # only for debugging
        self.sock = remote(self.device_ip, self.device_port) # you can replace it with a normal socket connection instead pwntools
        self.session_key = self.negotiate_session_key()
    
    def close(self):
        if self.sock:
            self.sock.close()
            self.sock = None
            self.session_key = None

    def tuya_encode(self, cmd, payload, key = None):
        if key is None:
            key = self.hmac_key
        
        msg_len = len(payload) + (struct.calcsize(MESSAGE_END_FMT_6699) - 4) + 12
        header_data = (PREFIX_6699_VALUE, 0, self.seqno, cmd, msg_len)

        data = struct.pack(MESSAGE_HEADER_FMT_6699, *header_data)

        iv = str(time.time() * 10)[:12].encode('utf8')
        aad = data[4:]

        cipher = Cipher(algorithms.AES(key), modes.GCM(iv), backend=default_backend())
        encryptor = cipher.encryptor()
        encryptor.authenticate_additional_data(aad)

        encrypted_payload = encryptor.update(payload) + encryptor.finalize()

        final_data = data + iv + encrypted_payload + encryptor.tag + SUFFIX_6699_BIN
        return final_data
    
    def tuya_decode(self, data: str, key: bytes = None):
        if key is None:
            key = self.hmac_key
        
        header_len = struct.calcsize(MESSAGE_HEADER_FMT_6699)
        if len(data) < header_len:
            return None

        header = struct.unpack(MESSAGE_HEADER_FMT_6699, data[:header_len])
        prefix, _, seqno, cmd, payload_len = header

        if prefix != PREFIX_6699_VALUE:
            print(f"Invalid prefix: {prefix}")
            return None

        iv_start = header_len
        iv_end = iv_start + 12
        encrypted_start = iv_end
        tag_start = header_len + payload_len - 16
        suffix_start = tag_start + 16

        if len(data) < suffix_start + 4:
            print(f"Message too short: expected {suffix_start + 4}, got {len(data)}")
            return None

        iv = data[iv_start:iv_end]
        encrypted_payload = data[encrypted_start:tag_start]
        tag = data[tag_start:suffix_start]
        suffix = data[suffix_start:suffix_start+4]

        if suffix != SUFFIX_6699_BIN:
            print(f"Invalid suffix: {suffix}")
            return None

        aad = data[4:header_len]

        cipher = Cipher(algorithms.AES(key), modes.GCM(iv, tag), backend=default_backend())
        decryptor = cipher.decryptor()
        decryptor.authenticate_additional_data(aad)

        decrypted_payload = decryptor.update(encrypted_payload) + decryptor.finalize()
        return {
                'seqno': seqno,
                'cmd': cmd,
                'payload': decrypted_payload
            }


    def negotiate_session_key(self):
        local_nonce = b'0123456789abcdef' # 理论上应随机
        remote_nonce = b''
        self.seqno += 1

        print("=== 会话密钥协商开始 ===")

        print("步骤 1: 发送 SESS_KEY_NEG_START")
        step1_msg = self.tuya_encode(SESS_KEY_NEG_START, local_nonce)
        self.sock.send(step1_msg)

        response_data = self.sock.recvrepeat(REPEAT)
        step2_response = self.tuya_decode(response_data)

        if not step2_response or step2_response['cmd'] != SESS_KEY_NEG_RESP:
            print("步骤 1 失败：未收到正确的 SESS_KEY_NEG_RESP")
            return None

        print("步骤 1 成功：收到 SESS_KEY_NEG_RESP")

        payload = step2_response['payload']

        remote_nonce = payload[4:20]
        received_hmac = payload[20:52]

        expected_hmac = hmac.new(self.hmac_key, local_nonce, hashlib.sha256).digest()
        if received_hmac != expected_hmac:
            print("步骤 2 失败：HMAC 验证失败")
            return None

        print("步骤 2 成功：HMAC 验证通过")

        print("步骤 3: 发送 SESS_KEY_NEG_FINISH")
        finish_hmac = hmac.new(self.hmac_key, remote_nonce, hashlib.sha256).digest()


        self.seqno += 1
        step3_msg = self.tuya_encode(SESS_KEY_NEG_FINISH, finish_hmac)
        self.sock.send(step3_msg)

        print("步骤 3 成功")

        print("步骤 4: 生成会话密钥")
        session_key = bytes([a ^ b for a, b in zip(local_nonce, remote_nonce)])

        cipher = AES.new(self.hmac_key, AES.MODE_GCM, local_nonce[:12])
        final_session_key = cipher.encrypt(session_key)

        print(f"会话密钥协商完成！最终密钥: {final_session_key.hex()}")
        return final_session_key

    def get_status(self):
        """获取设备状态信息"""
        print("=== 获取设备状态 ===")

        payload = b'{}'
        self.seqno += 1

        status_msg = self.tuya_encode(DP_QUERY_NEW, payload, self.session_key)
        self.sock.send(status_msg)

        response_data = self.sock.recvrepeat(REPEAT)
        status_response = self.tuya_decode(response_data, self.session_key)

        if not status_response:
            print("获取状态失败：未收到有效响应")
            return None

        if status_response['cmd'] != DP_QUERY_NEW:
            print(f"获取状态失败：收到意外的命令 {status_response['cmd']}")
            return None

        try:
            status_data = json.loads(status_response['payload'].strip(b'\x00').decode('utf-8'))
            return status_data
        except Exception as e:
            print(f"解析状态数据失败: {e}")
            print(f"原始payload: {status_response['payload']}")
            return None

    def set_status(self, on: Any, switch: int, nowait: bool = False):
        payload = {"protocol": 5, "t": int(time.time()), "data": {"dps": {switch: on}}}
        payload_bytes = header_version + json.dumps(payload).encode('utf-8')

        self.seqno += 1

        control_msg = self.tuya_encode(CONTROL_NEW, payload_bytes, self.session_key)
        self.sock.send(control_msg)

        if not nowait:
            response_data = self.sock.recvrepeat(REPEAT)
            control_response = self.tuya_decode(response_data, self.session_key)
            res = control_response['payload'][4 + 15:]
            return res

def main():
    d = NeuroLamp(HMAC_KEY, DEVICE_IP, DEVICE_PORT)
    print(d.get_status())
    print(d.set_status(True, 20))
    d.close()


if __name__ == "__main__":
    main()