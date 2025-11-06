## Swarm Sync Controller

[中文](README-zh.md) | English

A simple Swarm Sync controller for Neuro Lava Lamp using the TinyTuya library.

### Preparation

1. Install the `TinyTuya` library (if not already installed):

```bash
pip install tinytuya
```

2. Make sure your lava lamp is connected to the same Wi-Fi network as your PC, then run the scanner to get the device ID and IP address:

```bash
python -m tinytuya scan
```

Example return (some data has been redacted):

```
Unknown v3.5 Device   Product ID = ****************  [Valid Broadcast]:
    Address = 192.168.xx.yyy   Device ID = 0123456789abcdef012345 (len:22)  Local Key =   Version = 3.5  Type = default, MAC = 
    No Stats for 192.168.xx.yyy: DEVICE KEY required to poll for status
New Broadcast from App at 192.168.xx.zz - {'from': 'app', 'ip': '192.168.xx.zz'}
```

You need to note the device ID, IP address, and version number.

3. Get your local key by enabling `Troubleshooting Mode` in Swarm Sync.

Turn on `Troubleshooting Mode` in Swarm Sync settings, perform some control operations, then copy the `Debug logs`. Find the `localKey` in the copied log and record it.

4. Use the `TinyTuya` library to control the lava lamp.

## Control

1. First, establish a connection and authenticate

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

2. You can get the current status

```python
# You can get all status data like this
data = d.status()
print(f'Current Status: {data}')
```

You will get a response like this:

```json
{
    "dps": {
        "20": true,                             // Power, true=on, false=off
        "21": "colour",                         // Mode, colour=manual color selection, music=live sync
        "24": "00b803e803e8",                   // Color data
        "25": "****************************",   // (redacted) Not yet clear
        "26": 0,                                // Not yet clear
        "34": false                             // Not yet clear
    }
}
```

3. Control fields

You can use the d.set_status method to control the lava lamp. For example:

```python
# You can control the switch like this
d.set_status(True, 20)  # Turn on the lamp
# d.set_status(False, 20) # Turn off the lamp

# You can set the mode like this
d.set_status('colour', 21)  # Set mode to colour
# d.set_status('music', 21)   # Set mode to music (sync live stream)

# You can set the color like this
color_code = '00b403e803e8'
d.set_status(color_code, 24)
```

## Demo Video

[【NeuroLamp】熔岩灯本地控制脚本](https://www.bilibili.com/video/BV1qC1tBdEo7/)

## More

- For more updates and experimental research solutions, please refer to my personal blog [Swarm Sync 逆向笔记 - MetaMiku's Blog](https://metamiku.top/2025/11/01/Swarm-Sync-%E9%80%86%E5%90%91%E7%AC%94%E8%AE%B0/) 
- Our DIY replica project: [MetaMikuAI/NeuroLamp](https://github.com/MetaMikuAI/NeuroLamp)