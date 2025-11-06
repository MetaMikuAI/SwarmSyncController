import tinytuya
import tkinter as tk
from tkinter import ttk, colorchooser
import copy
import colorsys
from config import config

# Connect to Device
d = tinytuya.OutletDevice(
    dev_id=config['dev_id'],
    address=config['address'],
    local_key=config['local_key'],
    version=config['version']
)


def rgb_to_color_code(r, g, b):
    """Convert RGB to device-specific 12-character HSV color code HHHHSSSSVVVV"""
    h, s, v = colorsys.rgb_to_hsv(r/255.0, g/255.0, b/255.0)
    
    hue = int(h * 360)  # 0-360
    sat = int(s * 1000)  # 0-1000
    val = int(v * 1000)  # 0-1000
    
    return f"{hue:04x}{sat:04x}{val:04x}"

class LightController:
    def __init__(self, root):
        self.root = root
        self.root.title("Swarm Sync")
        self.root.geometry("250x150")

        main_frame = ttk.Frame(root)
        main_frame.pack(expand=True, fill=tk.BOTH, padx=20, pady=20)

        row1_frame = ttk.Frame(main_frame)
        row1_frame.pack(pady=5)

        self.on_button = ttk.Button(row1_frame, text="Turn On", command=self.turn_on, width=10)
        self.on_button.pack(side=tk.LEFT, padx=5)

        self.off_button = ttk.Button(row1_frame, text="Turn Off", command=self.turn_off, width=10)
        self.off_button.pack(side=tk.LEFT, padx=5)

        row2_frame = ttk.Frame(main_frame)
        row2_frame.pack(pady=5)

        self.color_button = ttk.Button(row2_frame, text="Set Color", command=self.choose_color, width=10)
        self.color_button.pack(side=tk.LEFT, padx=5)

        self.status_button = ttk.Button(row2_frame, text="Get Status", command=self.update_status, width=10)
        self.status_button.pack(side=tk.LEFT, padx=5)

        author_label = ttk.Label(root, text="by MetaMIku", font=("Arial", 8))
        author_label.pack(side=tk.BOTTOM, pady=5)

    def turn_on(self):
        d.set_status(True, 20)

    def turn_off(self):
        d.set_status(False, 20)

    def choose_color(self):
        color = colorchooser.askcolor(title="Choose Color")
        if color[0] is not None:
            r, g, b = [int(x) for x in color[0]]
            color_code = rgb_to_color_code(r, g, b)
            d.set_status(color_code, 24)

    def update_status(self):
        data = d.status()
        print(f'Current Status: {data}')

if __name__ == "__main__":
    root = tk.Tk()
    app = LightController(root)
    root.mainloop()

