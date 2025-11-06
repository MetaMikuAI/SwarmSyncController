## Swarm Sync Controller

简体中文 | [English](README.md)

一个简单的 Swarm Sync 控制器，使用 TinyTuya 库来控制 Neuro Lava Lamp。

### 准备工作

1. 安装 `TinyTuya` 库（如果尚未安装）：

```bash
pip install tinytuya
```

2. 确保熔岩灯与您的 PC 连接到同一 Wi-Fi 网络，然后运行扫描程序以获取设备 ID 和 IP 地址：

```bash
python -m tinytuya scan
```

示例返回（部分数据已脱敏）

```
Unknown v3.5 Device   Product ID = ****************  [Valid Broadcast]:
    Address = 192.168.xx.yyy   Device ID = 0123456789abcdef012345 (len:22)  Local Key =   Version = 3.5  Type = default, MAC = 
    No Stats for 192.168.xx.yyy: DEVICE KEY required to poll for status
New Broadcast from App at 192.168.xx.zz - {'from': 'app', 'ip': '192.168.xx.zz'}
```

您需要关注的是设备 ID、IP 地址和以及版本号。

3. 通过在 Swarm Sync 中启用 `故障排查模式`(`Troubleshooting Mode`) 来获取本地密钥。

在 Swarm Sync 设置中打开 `故障排查模式`(`Troubleshooting Mode`)，进行一定控制操作，然后复制 `调试日志`(`Debug logs`)，在复制的日志中找到 `localKey` 并记录。

4. 借助 `TinyTuya` 库来控制熔岩灯。

## 控制

1. 首先需要建立连接并鉴权

```python
import tinytuya

# Connect to Device
d = tinytuya.OutletDevice(
    dev_id='0123456789abcdef012345',
    address='192.168.xx.yyy',
    local_key='****************',
    version=3.5
)
```

2. 可以获取当前状态

```python
# You can get all status data like this
data = d.status()
print(f'Current Status: {data}')
```

会得到一个形如下的响应：

```json
{
    "dps": {
        "20": true,                             // 电源，true=开, false=关
        "21": "colour",                         // 模式，colour=手动取色, music = 直播同步
        "24": "00b803e803e8",                   // 颜色数据
        "25": "****************************",   // (已脱敏)尚不清楚
        "26": 0,                                // 尚不清楚
        "34": false                             // 尚不清楚
    }
}
```

3. 控制字段

您可以使用 d.set_status 方法来控制熔岩灯。 例如：

```python
# 你可以像这样控制开关
d.set_status(True, 20)  # 开灯
# d.set_status(False, 20) # 关灯

# 你可以像这样设置模式
d.set_status('colour', 21)  # 设置模式为颜色
# d.set_status('music', 21)   # 设置模式为音乐（同步直播流）

# 你可以像这样设置颜色
color_code = '00b403e803e8'
d.set_status(color_code, 24)
```

## 演示视频

[【NeuroLamp】熔岩灯本地控制脚本](https://www.bilibili.com/video/BV1qC1tBdEo7/)

## 其他

更多更新、实验性的研究方案可以参考我的个人博客 [Swarm Sync 逆向笔记 - MetaMiku's Blog](https://metamiku.top/2025/11/01/Swarm-Sync-%E9%80%86%E5%90%91%E7%AC%94%E8%AE%B0/)