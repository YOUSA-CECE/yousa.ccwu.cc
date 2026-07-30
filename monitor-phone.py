#!/usr/bin/env python3
"""
📊 手机服务器性能监测工具
华为 Mate 9 / Termux 环境专用
运行: python3 monitor.py [port]
"""

import os
import re
import time
import json
import socket
import subprocess
import threading
from pathlib import Path
from datetime import datetime, timedelta

try:
    from flask import Flask, jsonify, render_template_string, request
except ImportError:
    Flask = None

# ── 系统数据采集 ───────────────────────────────────────────────

PROC = Path("/proc")


def _read_proc(path: Path) -> str:
    """安全读取 /proc 文件"""
    try:
        return path.read_text()
    except (OSError, FileNotFoundError, PermissionError):
        return ""


def get_cpu_count():
    """获取 CPU 核心数（硬件实际核心数）"""
    # Termux 被 cpuset 限制只能看到小核，要用 kernel_max 获取真实核心数
    try:
        possible = Path("/sys/devices/system/cpu/possible")
        if possible.exists():
            raw = possible.read_text().strip()
            # Format: "0-7"
            m = re.match(r'0-(\d+)', raw)
            if m:
                return int(m.group(1)) + 1
        kernel_max = Path("/sys/devices/system/cpu/kernel_max")
        if kernel_max.exists():
            return int(kernel_max.read_text().strip()) + 1
    except Exception:
        pass
    # Fallback to nproc (cgroup-limited, may under-report)
    try:
        out = subprocess.check_output(["nproc"], timeout=3, text=True).strip()
        return int(out)
    except Exception:
        return 4


def get_cpu_usage():
    """返回缓存的 CPU 使用率"""
    global _cached_cpu
    return _cached_cpu


def get_system_cpu_usage():
    """
    获取系统级 CPU 使用率（通过 dumpsys cpuinfo 的 adb shell）
    仅作为参考值，在单独的字段返回
    """
    return None


def get_per_cpu_usage():
    """获取每个核心的使用率（Android 上不可用，返回空）"""
    return {}


# ── 全局状态（进程内持久） ────────────────────────────────────

_cached_procs = []
_stats_lock = threading.Lock()

# 网络速度采样历史（用于实时显示）
_net_prev = None  # (data_dict, timestamp)
# 趋势历史（最多保留 120 个点）
_cpu_history = []
_mem_history = []
_net_rx_history = []
_net_tx_history = []
_time_history = []


def _calc_network_speed():
    """计算网络实时速度，优先用 wlan0（内部函数）"""
    global _net_prev
    raw = _read_proc(PROC / "net" / "dev")
    now = {}
    for line in raw.splitlines()[2:]:
        parts = line.strip().split()
        if len(parts) >= 10:
            iface = parts[0].rstrip(":")
            if iface == "lo":
                continue
            now[iface] = (int(parts[1]), int(parts[9]))  # rx, tx
    
    if _net_prev is None:
        _net_prev = (now, time.time())
        return {}
    
    prev_data, prev_time = _net_prev
    now_time = time.time()
    elapsed = now_time - prev_time
    _net_prev = (now, now_time)
    
    if elapsed <= 0:
        return {}
    
    speeds = {}
    for iface, (rx, tx) in now.items():
        prev_rx, prev_tx = prev_data.get(iface, (rx, tx))
        rx_speed = max(0, (rx - prev_rx) / elapsed)
        tx_speed = max(0, (tx - prev_tx) / elapsed)
        speeds[iface] = {
            "rx_kbps": round(rx_speed * 8 / 1024, 1),
            "tx_kbps": round(tx_speed * 8 / 1024, 1),
            "rx_kBs": round(rx_speed / 1024, 1),
            "tx_kBs": round(tx_speed / 1024, 1),
        }
    return speeds


def record_history(cpu_usage, mem_used, net_speed):
    """记录趋势历史"""
    global _cpu_history, _mem_history, _net_rx_history, _net_tx_history, _time_history
    
    now = datetime.now().strftime("%H:%M:%S")
    _time_history.append(now)
    _cpu_history.append(cpu_usage)
    _mem_history.append(mem_used)
    
    # 取 wlan0 的网络速度，没有则取第一个非空
    rx = tx = 0
    if net_speed:
        if "wlan0" in net_speed:
            rx = net_speed["wlan0"]["rx_kbps"]
            tx = net_speed["wlan0"]["tx_kbps"]
        else:
            first = next(iter(net_speed.values()))
            rx, tx = first["rx_kbps"], first["tx_kbps"]
    _net_rx_history.append(rx)
    _net_tx_history.append(tx)
    
    # 最多保留 120 个点
    MAX = 120
    for lst in (_time_history, _cpu_history, _mem_history, _net_rx_history, _net_tx_history):
        while len(lst) > MAX:
            lst.pop(0)



def get_cpu_usage():
    """
    实时采集 CPU 使用率（%）
    用 top -bn1 头部 CPU 行（系统级统计数据），避免累加各进程 CPU 导致的假尖峰
    """
    try:
        out = subprocess.check_output(
            ["top", "-bn1"], timeout=8, text=True
        )
        procs = []
        total_cpu = 0.0
        cpu_count = get_cpu_count()
        started = False
        for line in out.splitlines():
            # 解析进程列表（从 each 进程的 CPU% 累加，再除核心数）
            if "PID" in line and "USER" in line and "%CPU" in line:
                started = True
                continue
            if not started:
                continue
            parts = line.strip().split()
            if len(parts) < 10:
                continue
            try:
                pid = parts[0]
                name = parts[-1]
                cmd = " ".join(parts[11:]) if len(parts) > 11 else parts[-1]
                if cmd == "start-monitor.sh":
                    continue
                cpu_str = parts[8].rstrip("%")
                total_cpu += float(cpu_str)
                
                res_raw = parts[5]
                if res_raw.endswith("M"):
                    res_kb = int(float(res_raw.rstrip("M")) * 1024)
                elif res_raw.endswith("G"):
                    res_kb = int(float(res_raw.rstrip("G")) * 1024 * 1024)
                elif res_raw.endswith("K"):
                    res_kb = int(float(res_raw.rstrip("K")))
                else:
                    res_kb = int(float(res_raw))
                procs.append({
                    "pid": pid,
                    "name": name[:60],
                    "cmdline": cmd[:120],
                    "state": parts[7],
                    "mem_mb": round(res_kb / 1024, 1),
                })
            except (ValueError, IndexError):
                continue
        
        procs.sort(key=lambda p: p["mem_mb"], reverse=True)
        with _stats_lock:
            global _cached_procs
            _cached_procs = procs[:20]
        
        # 将进程 CPU% 总和除以核心数，归一化到 0~100%
        cpu_usage = round(min(total_cpu / max(cpu_count, 1), 100.0), 1)
        return cpu_usage
    except Exception:
        return 0.0
def get_memory():
    """获取内存信息"""
    # Use free command which works in Termux
    try:
        out = subprocess.check_output(["free", "-k"], timeout=3, text=True)
        lines = out.splitlines()
        for line in lines:
            if line.startswith("Mem:"):
                parts = line.split()
                total = round(int(parts[1]) / 1024, 1)
                used = round(int(parts[2]) / 1024, 1)
                free = round(int(parts[3]) / 1024, 1)
                available = round(int(parts[6]) / 1024, 1)
                return {
                    "total": total, "free": free, "available": available,
                    "used": used, "buffers": 0, "cached": 0,
                }
    except Exception:
        pass
    # Fallback to /proc/meminfo
    meminfo = _read_proc(PROC / "meminfo")
    data = {}
    for line in meminfo.splitlines():
        m = re.match(r'^(\w+):\s+(\d+)\s+kB', line)
        if m:
            data[m.group(1)] = round(int(m.group(2)) / 1024, 1)
    return {
        "total": data.get("MemTotal", 0),
        "free": data.get("MemFree", 0),
        "available": data.get("MemAvailable", data.get("MemFree", 0)),
        "used": data.get("MemTotal", 0) - data.get("MemAvailable", data.get("MemFree", 0)),
        "buffers": data.get("Buffers", 0),
        "cached": data.get("Cached", 0),
    }


def get_swap():
    """获取交换分区信息"""
    try:
        out = subprocess.check_output(["free", "-k"], timeout=3, text=True)
        for line in out.splitlines():
            if line.startswith("Swap:"):
                parts = line.split()
                return {"SwapTotal": round(int(parts[1]) / 1024, 1), "SwapFree": round(int(parts[3]) / 1024, 1)}
    except Exception:
        pass
    return {"SwapTotal": 0, "SwapFree": 0}


def get_loadavg():
    """获取系统负载"""
    try:
        out = subprocess.check_output(["uptime"], timeout=3, text=True)
        m = re.search(r'load average[s]?:\s+([\d.]+),\s+([\d.]+),\s+([\d.]+)', out)
        if m:
            return {"1min": float(m.group(1)), "5min": float(m.group(2)), "15min": float(m.group(3))}
    except Exception:
        pass
    return {"1min": 0, "5min": 0, "15min": 0}


def get_uptime():
    """获取系统运行时间"""
    try:
        out = subprocess.check_output(["uptime"], timeout=3, text=True)
        # Format: "02:07:06 up  3:19,  0 users,  load average: ..."
        m = re.search(r'up\s+(\d+)\s*days?,\s+(\d+):(\d+)', out)
        if m:
            days, hours, mins = int(m.group(1)), int(m.group(2)), int(m.group(3))
        else:
            m = re.search(r'up\s+(\d+):(\d+),\s+\d+ users', out)
            if m:
                days, hours, mins = 0, int(m.group(1)), int(m.group(2))
            else:
                m = re.search(r'up\s+(\d+)\s+min', out)
                if m:
                    days, hours, mins = 0, 0, int(m.group(1))
                else:
                    return {"seconds": 0, "days": 0, "hours": 0, "minutes": 0, "text": "未知"}
        return {
            "seconds": days * 86400 + hours * 3600 + mins * 60,
            "days": days, "hours": hours, "minutes": mins,
            "text": f"{days}天 {hours}小时 {mins}分钟",
        }
    except Exception:
        return {"seconds": 0, "days": 0, "hours": 0, "minutes": 0, "text": "未知"}


def get_disk():
    """获取存储使用情况 (Termux 目录)"""
    try:
        import os
        home = str(Path.home())
        st = os.statvfs(home)
        total = st.f_blocks * st.f_frsize
        free = st.f_bfree * st.f_frsize
        used = total - free
        return {
            "total_gb": round(total / 1024**3, 2),
            "used_gb": round(used / 1024**3, 2),
            "free_gb": round(free / 1024**3, 2),
            "used_pct": round(used / total * 100, 1) if total > 0 else 0,
        }
    except Exception:
        return {"total_gb": 0, "used_gb": 0, "free_gb": 0, "used_pct": 0}


def get_network():
    """获取网络流量统计"""
    raw = _read_proc(PROC / "net" / "dev")
    interfaces = {}
    for line in raw.splitlines()[2:]:  # 跳过前两行标题
        parts = line.strip().split()
        if len(parts) >= 10:
            iface = parts[0].rstrip(":")
            # 跳过回环接口
            if iface == "lo":
                continue
            rx_bytes = int(parts[1])
            tx_bytes = int(parts[9])
            rx_packets = int(parts[2])
            tx_packets = int(parts[10])
            interfaces[iface] = {
                "rx_bytes": rx_bytes,
                "tx_bytes": tx_bytes,
                "rx_mb": round(rx_bytes / 1024 / 1024, 2),
                "tx_mb": round(tx_bytes / 1024 / 1024, 2),
                "rx_packets": rx_packets,
                "tx_packets": tx_packets,
            }
    return interfaces


def get_network_speed():
    """计算网络实时速度"""
    return _calc_network_speed()



def get_temperature():
    """获取 CPU 温度（从 /sys/devices/virtual/thermal/ 读取）"""
    # Termux 没权限读 /sys/class/thermal，但能读 /sys/devices/virtual/thermal/
    thermal_base = Path("/sys/devices/virtual/thermal")
    if not thermal_base.exists():
        return None
    
    temps = {}
    for zone_dir in sorted(thermal_base.glob("thermal_zone*")):
        try:
            type_name = (zone_dir / "type").read_text().strip()
            temp_raw = (zone_dir / "temp").read_text().strip()
            temp_val = int(temp_raw)
            # 有些 zone 的值明显不对（个位数），跳过
            if temp_val < 1000:
                continue
            temps[type_name or f"zone{zone_dir.name[-2:]}"] = round(temp_val / 1000, 1)
        except (OSError, ValueError):
            continue
    
    if not temps:
        return None
    
    # 返回最有代表性的
    for preferred in ("soc_thermal", "system_h", "Battery", "cluster0", "cluster1"):
        if preferred in temps:
            return {"type": preferred, "temp_c": temps[preferred]}
    
    # 取第一个有效的
    first = next(iter(temps.items()))
    return {"type": first[0], "temp_c": first[1]}


def get_process_list():
    """进程信息改为后台线程采集，详见 _collect_stats_bg"""
    global _cached_procs
    return _cached_procs


def get_wifi_info():
    """获取 WiFi 信息"""
    wireless = _read_proc(PROC / "net" / "wireless")
    for line in wireless.splitlines():
        if "wlan" in line:
            parts = line.strip().split()
            if len(parts) >= 4:
                # status, quality=link, signal, noise
                quality = parts[2].rstrip(".")
                try:
                    q_num = int(quality)
                    # Convert link quality (0-70) to percentage
                    q_pct = min(100, round(q_num / 70 * 100))
                    return {"quality": quality, "quality_pct": q_pct}
                except ValueError:
                    return {"quality": quality, "quality_pct": 0}
    return None


def get_all_stats():
    """采集所有系统指标"""
    cpu_usage = get_cpu_usage()
    per_cpu = get_per_cpu_usage()
    mem = get_memory()
    load = get_loadavg()
    uptime = get_uptime()
    disk = get_disk()
    net = get_network()
    net_speed = get_network_speed()
    temp = get_temperature()
    wifi = get_wifi_info()
    cpu_count = get_cpu_count()
    
    with _stats_lock:
        procs = list(_cached_procs)
    
    # 记录趋势历史
    record_history(cpu_usage, mem["used"], net_speed)

    return {
        "timestamp": datetime.now().strftime("%H:%M:%S"),
        "cpu": {
            "usage": cpu_usage,
            "per_core": per_cpu,
            "count": cpu_count,
            "load": load,
            "temp": temp,
        },
        "memory": mem,
        "swap": get_swap(),
        "uptime": uptime,
        "disk": disk,
        "network": {
            "interfaces": net,
            "speed": net_speed,
        },
        "wifi": wifi,
        "processes": procs,
        "history": {
            "timestamps": _time_history[-60:],
            "cpu": _cpu_history[-60:],
            "mem": _mem_history[-60:],
            "net_rx": _net_rx_history[-60:],
            "net_tx": _net_tx_history[-60:],
        },
    }

# ── 启动 ────────────────────────────────────────────────────────

DASHBOARD_HTML = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1.0,maximum-scale=1.0,user-scalable=no">
<title>📊 手机服务器监控</title>
<style>
:root {
  --bg: #0d1117;
  --card: #161b22;
  --card-hover: #1c2333;
  --border: #30363d;
  --text: #e6edf3;
  --text-dim: #8b949e;
  --accent: #00d4aa;
  --accent-dim: rgba(0,212,170,0.15);
  --warn: #f0883e;
  --danger: #f85149;
  --radius: 12px;
}
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','Noto Sans SC',sans-serif;background:var(--bg);color:var(--text);padding:16px;min-height:100vh}
.header{display:flex;justify-content:space-between;align-items:center;margin-bottom:20px;flex-wrap:wrap;gap:8px}
.header h1{font-size:1.3rem;font-weight:600;display:flex;align-items:center;gap:8px}
.header h1 .dim{color:var(--text-dim);font-weight:400;font-size:0.8rem}
.refresh-info{font-size:0.78rem;color:var(--text-dim)}
.refresh-info .dot{display:inline-block;width:8px;height:8px;border-radius:50%;background:var(--accent);margin-right:6px;animation:pulse 2s ease-in-out infinite}
@keyframes pulse{0%,100%{opacity:1}50%{opacity:.3}}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:12px;margin-bottom:16px}
.card{background:var(--card);border:1px solid var(--border);border-radius:var(--radius);padding:16px;transition:background .2s}
.card:hover{background:var(--card-hover)}
.card-title{font-size:.75rem;text-transform:uppercase;letter-spacing:.06em;color:var(--text-dim);margin-bottom:10px;display:flex;align-items:center;gap:6px}
.card-value{font-size:1.8rem;font-weight:700}
.card-value .unit{font-size:.9rem;font-weight:400;color:var(--text-dim);margin-left:4px}
.card-sub{font-size:.8rem;color:var(--text-dim);margin-top:4px}
.bar-container{margin-top:10px;height:6px;background:rgba(255,255,255,.08);border-radius:3px;overflow:hidden}
.bar{height:100%;border-radius:3px;transition:width .5s ease}
.bar.green{background:var(--accent)}
.bar.orange{background:var(--warn)}
.bar.red{background:var(--danger)}
.chart-container{background:var(--card);border:1px solid var(--border);border-radius:var(--radius);padding:16px;margin-bottom:12px}
.chart-container.full-width{grid-column:1/-1}
.chart-title{font-size:.75rem;text-transform:uppercase;letter-spacing:.06em;color:var(--text-dim);margin-bottom:12px}
.chart{width:100%;height:120px;position:relative}
.chart svg{width:100%;height:100%}
.process-table{width:100%;border-collapse:collapse;font-size:.78rem}
.process-table th{text-align:left;color:var(--text-dim);font-weight:500;padding:6px 8px;border-bottom:1px solid var(--border);white-space:nowrap}
.process-table td{padding:5px 8px;border-bottom:1px solid rgba(48,54,61,.5);white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:200px}
.process-table tr:hover td{background:rgba(255,255,255,.03)}
.pid{color:var(--text-dim);font-family:'SF Mono',monospace}
.mem-bar{display:inline-block;width:60px;height:4px;background:rgba(255,255,255,.08);border-radius:2px;vertical-align:middle;margin-right:6px}
.mem-bar-fill{height:100%;border-radius:2px;background:var(--accent)}
.net-grid{display:grid;grid-template-columns:1fr 1fr;gap:8px}
.net-stat{text-align:center;padding:8px;background:rgba(255,255,255,.03);border-radius:8px}
.net-stat .label{font-size:.7rem;color:var(--text-dim)}
.net-stat .value{font-size:1.2rem;font-weight:600}
.net-stat .value.down{color:#58a6ff}
.net-stat .value.up{color:var(--warn)}
.iface-section{margin-top:8px}
.iface-name{font-family:'SF Mono',monospace;font-size:.7rem;color:var(--text-dim);margin-bottom:4px}
.cpu-cores{display:flex;gap:4px;flex-wrap:wrap;margin-top:8px}
.core-pill{background:rgba(255,255,255,.05);border-radius:4px;padding:2px 6px;font-size:.65rem;font-family:'SF Mono',monospace}
.core-pill.high{color:var(--danger)}
.core-pill.med{color:var(--warn)}
.core-pill.low{color:var(--accent)}
@media(max-width:600px){body{padding:10px}.grid{grid-template-columns:1fr}.card-value{font-size:1.4rem}.process-table td{max-width:120px}}
</style>
</head>
<body>
<div class="header">
  <h1>📊 <span class="dim">手机服务器</span> 性能监控</h1>
  <div class="refresh-info">
    <span class="dot"></span>实时更新 · <span id="lastUpdate">刚刚</span>
  </div>
</div>

<div class="grid" id="statsGrid">
  <div class="card"><div class="card-title">🖥️ CPU 使用率</div><div class="card-value" id="cardCpuVal">--<span class="unit">%</span></div><div class="card-sub" id="cardCpuSub">加载中...</div><div class="bar-container"><div class="bar" id="cardCpuBar" style="width:0%"></div></div><div class="cpu-cores" id="cardCpuCores"></div></div>
  <div class="card"><div class="card-title">🌡️ CPU 温度</div><div class="card-value" id="cardTempVal">--<span class="unit">°C</span></div></div>
  <div class="card"><div class="card-title">🧠 内存</div><div class="card-value" id="cardMemVal">--<span class="unit">MB</span></div><div class="card-sub" id="cardMemSub">加载中...</div><div class="bar-container"><div class="bar" id="cardMemBar" style="width:0%"></div></div></div>
  <div class="card"><div class="card-title">💾 存储 (Termux)</div><div class="card-value" id="cardDiskVal">--<span class="unit">GB</span></div><div class="card-sub" id="cardDiskSub">加载中...</div><div class="bar-container"><div class="bar" id="cardDiskBar" style="width:0%"></div></div></div>
  <div class="card"><div class="card-title">⏱️ 运行时间</div><div class="card-value" id="cardUptimeVal">--</div><div class="card-sub">手机服务器</div></div>
  <div class="card"><div class="card-title">📶 WiFi 信号</div><div class="card-value" id="cardWifiVal">--<span class="unit">%</span></div><div class="card-sub" id="cardWifiSub">加载中...</div></div>
</div>

<div class="grid">
  <div class="chart-container full-width">
    <div class="chart-title">📈 CPU & 内存趋势 (最近 60 个采样点)</div>
    <div class="chart" id="trendChart"><svg viewBox="0 0 600 120"></svg></div>
  </div>
</div>

<div class="grid">
  <div class="chart-container full-width">
    <div class="chart-title">📈 网络流量趋势</div>
    <div class="chart" id="netChart"><svg viewBox="0 0 600 120"></svg></div>
  </div>
</div>

<div class="grid" id="networkGrid">
  <div class="card"><div class="card-title">🌐 实时网络</div><div class="net-grid"><div class="net-stat"><div class="label">⬇ 下载</div><div class="value down" id="netDown">-- Kbps</div></div><div class="net-stat"><div class="label">⬆ 上传</div><div class="value up" id="netUp">-- Kbps</div></div></div></div>
  <div class="card"><div class="card-title">📦 网络流量累计</div><div id="networkTotals">加载中...</div></div>
</div>

<div class="card" id="processCard">
  <div class="card-title">⚙️ 进程 TOP 20 (按内存排序)</div>
  <div style="overflow-x:auto;">
    <table class="process-table">
      <thead><tr><th>PID</th><th>名称</th><th>内存</th><th>线程</th><th>状态</th></tr></thead>
      <tbody id="processBody">
        <tr><td colspan="5" style="text-align:center;color:var(--text-dim);padding:20px">加载中...</td></tr>
      </tbody>
    </table>
  </div>
</div>

<script>
let HISTORY = {cpu:[],mem:[],net_rx:[],net_tx:[],timestamps:[]};

function renderSvgChart(containerId, data, color, maxVal) {
  const container = document.getElementById(containerId);
  if (!container) return;
  let svg = container.querySelector('svg');
  if (!svg) { svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg'); svg.setAttribute('viewBox','0 0 600 120'); container.appendChild(svg); }
  if (!data || data.length < 2) return;
  const w = 600, h = 120, pt = 8, pb = 20, pl = 4, pr = 4;
  const cw = w - pl - pr, ch = h - pt - pb;
  if (!maxVal) maxVal = Math.max(...data, 1) * 1.15;
  const points = data.map((v, i) => {
    const x = pl + (i / Math.max(data.length - 1, 1)) * cw;
    const y = pt + ch - (v / maxVal) * ch;
    return x+','+y;
  }).join(' ');
  let html = '<path d="M'+points+'" fill="none" stroke="'+color+'" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>';
  html += '<path d="M'+points+' L'+(pl+cw)+','+(pt+ch)+' L'+pl+','+(pt+ch)+' Z" fill="'+color+'20"/>';
  const last = data[data.length - 1] || 0;
  const lx = pl + cw, ly = pt + ch - (last / maxVal) * ch;
  html += '<circle cx="'+lx+'" cy="'+ly+'" r="3" fill="'+color+'"/>';
  html += '<text x="'+(lx+5)+'" y="'+(ly+4)+'" fill="'+color+'" font-size="10" font-weight="600">'+(typeof last==='number'?last.toFixed(1):last)+'</text>';
  html += '<text x="'+(pl-2)+'" y="'+(pt+8)+'" fill="#8b949e" font-size="9" text-anchor="end">'+Math.round(maxVal)+'</text>';
  html += '<text x="'+(pl-2)+'" y="'+(pt+ch)+'" fill="#8b949e" font-size="9" text-anchor="end">0</text>';
  svg.innerHTML = html;
}

function renderMemChart(data, color) {
  const container = document.getElementById('trendChart');
  if (!container) return;
  let svg = container.querySelector('svg');
  if (!svg || !data || data.length < 2) return;
  const w = 600, h = 120, pt = 8, pb = 20, pl = 4, pr = 4;
  const cw = w - pl - pr, ch = h - pt - pb;
  const maxV = Math.max(...data, 100) * 1.15;
  const points = data.map((v, i) => {
    const x = pl + (i / Math.max(data.length - 1, 1)) * cw;
    const y = pt + ch - (v / maxV) * ch;
    return x+','+y;
  }).join(' ');
  const last = data[data.length - 1] || 0;
  const lx = pl + cw, ly = pt + ch - (last / maxV) * ch;
  let extra = '<path d="M'+points+'" fill="none" stroke="'+color+'" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" stroke-dasharray="4,3"/>';
  extra += '<circle cx="'+lx+'" cy="'+ly+'" r="3" fill="'+color+'"/>';
  extra += '<text x="'+(lx+5)+'" y="'+(ly+16)+'" fill="'+color+'" font-size="9">Mem '+last.toFixed(0)+' MB</text>';
  svg.innerHTML += extra;
}

function updateCharts(h) {
  renderSvgChart('trendChart', h.cpu, '#00d4aa', 100);
  renderMemChart(h.mem, '#58a6ff');
  renderSvgChart('netChart', h.net_rx, '#58a6ff');
  if (h.net_tx && h.net_tx.length > 1) {
    const container = document.getElementById('netChart');
    const svg = container.querySelector('svg');
    if (svg) {
      const w = 600, hh = 120, pt = 8, pb = 20, pl = 4, pr = 4;
      const cw = w - pl - pr, ch = hh - pt - pb;
      const maxV = Math.max(Math.max(...h.net_rx, 1), Math.max(...h.net_tx, 1)) * 1.15;
      const pts = h.net_tx.map((v, i) => {
        const x = pl + (i / Math.max(h.net_tx.length - 1, 1)) * cw;
        const y = pt + ch - (v / maxV) * ch;
        return x+','+y;
      }).join(' ');
      svg.innerHTML += '<path d="M'+pts+'" fill="none" stroke="#f0883e" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" stroke-dasharray="4,3"/>';
    }
  }
}

function updateCards(s) {
  const cpu = s.cpu, mem = s.memory, disk = s.disk, uptime = s.uptime, wifi = s.wifi, load = cpu.load;
  const cpuPct = cpu.usage;
  const cpuBarCls = cpuPct > 80 ? 'red' : cpuPct > 50 ? 'orange' : 'green';
  document.getElementById('cardCpuVal').innerHTML = cpuPct.toFixed(1) + '<span class="unit">%</span>';
  document.getElementById('cardCpuBar').style.width = cpuPct + '%';
  document.getElementById('cardCpuBar').className = 'bar ' + cpuBarCls;
  document.getElementById('cardCpuSub').textContent = cpu.count + ' 核 · 负载 ' + load['1min'].toFixed(2) + ' / ' + load['5min'].toFixed(2) + ' / ' + load['15min'].toFixed(2);
  let coreHtml = '';
  for (const [core, val] of Object.entries(cpu.per_core)) {
    const cls = val > 80 ? 'high' : val > 50 ? 'med' : 'low';
    coreHtml += '<span class="core-pill ' + cls + '">' + core + ' ' + val.toFixed(0) + '%</span>';
  }
  if (coreHtml) document.getElementById('cardCpuCores').innerHTML = coreHtml;
  if (cpu.temp) document.getElementById('cardTempVal').innerHTML = cpu.temp.temp_c + '<span class="unit">°C</span>';
  const memPct = mem.total > 0 ? (mem.used / mem.total * 100) : 0;
  const memBarCls = memPct > 80 ? 'red' : memPct > 60 ? 'orange' : 'green';
  document.getElementById('cardMemVal').innerHTML = mem.used.toFixed(0) + '<span class="unit">MB</span>';
  document.getElementById('cardMemSub').textContent = '/ ' + mem.total.toFixed(0) + ' MB (' + memPct.toFixed(0) + '%) · 可用 ' + mem.available.toFixed(0) + ' MB';
  document.getElementById('cardMemBar').style.width = memPct + '%';
  document.getElementById('cardMemBar').className = 'bar ' + memBarCls;
  document.getElementById('cardDiskVal').innerHTML = disk.used_gb.toFixed(1) + '<span class="unit">GB</span>';
  document.getElementById('cardDiskSub').textContent = '已用 ' + disk.used_pct.toFixed(0) + '% / 共 ' + disk.total_gb.toFixed(1) + ' GB';
  document.getElementById('cardDiskBar').style.width = Math.min(disk.used_pct, 100) + '%';
  document.getElementById('cardDiskBar').className = 'bar ' + (disk.used_pct > 80 ? 'red' : disk.used_pct > 60 ? 'orange' : 'green');
  document.getElementById('cardUptimeVal').textContent = uptime.text;
  if (wifi) { document.getElementById('cardWifiVal').innerHTML = wifi.quality_pct + '<span class="unit">%</span>'; document.getElementById('cardWifiSub').textContent = '信号质量: ' + wifi.quality; }
  const netSpeed = s.network.speed;
  let firstIface = Object.keys(netSpeed)[0];
  if (netSpeed.wlan0) firstIface = 'wlan0';
  if (firstIface) { document.getElementById('netDown').textContent = netSpeed[firstIface].rx_kbps.toFixed(1) + ' Kbps'; document.getElementById('netUp').textContent = netSpeed[firstIface].tx_kbps.toFixed(1) + ' Kbps'; }
  let netHtml = '';
  for (const [iface, data] of Object.entries(s.network.interfaces)) {
    netHtml += '<div class="iface-section"><div class="iface-name">' + iface + '</div><div class="net-grid"><div class="net-stat"><div class="label">⬇ 已接收</div><div class="value down">' + data.rx_mb.toFixed(1) + ' MB</div></div><div class="net-stat"><div class="label">⬆ 已发送</div><div class="value up">' + data.tx_mb.toFixed(1) + ' MB</div></div></div></div>';
  }
  document.getElementById('networkTotals').innerHTML = netHtml;
  let procHtml = '';
  for (const p of s.processes) {
    const pct = s.memory.total > 0 ? (p.mem_mb / s.memory.total * 500) : 0;
    procHtml += '<tr><td><span class="pid">' + p.pid + '</span></td><td title="' + p.cmdline.replace(/"/g,'&quot;').slice(0,200) + '">' + p.name.slice(0,50) + '</td><td><div class="mem-bar"><div class="mem-bar-fill" style="width:' + Math.min(pct, 100) + '%"></div></div>' + p.mem_mb.toFixed(1) + ' MB</td><td>' + (p.threads || 0) + '</td><td>' + (p.state || '').split('(')[0].trim() + '</td></tr>';
  }
  if (procHtml) document.getElementById('processBody').innerHTML = procHtml;
}

async function refresh() {
  try {
    const resp = await fetch('/api/stats');
    const data = await resp.json();
    document.getElementById('lastUpdate').textContent = '刚刚';
    updateCards(data);
    HISTORY = data.history || HISTORY;
    updateCharts(HISTORY);
  } catch(e) { document.getElementById('lastUpdate').textContent = '⚠️ 更新失败: ' + e.message; }
}

document.addEventListener('DOMContentLoaded', () => { refresh(); setInterval(refresh, 3000); });
</script>
</body>
</html>"""


def create_app():
    app = Flask(__name__)
    
    @app.route("/")
    def dashboard():
        return DASHBOARD_HTML
    
    @app.route("/api/stats")
    def api_stats():
        return jsonify(get_all_stats())
    
    return app


# ── 启动 ────────────────────────────────────────────────────────

def main():
    port = int(os.environ.get("MONITOR_PORT", os.environ.get("PORT", 5001)))
    host = os.environ.get("HOST", "0.0.0.0")
    
    print(f"  📊 手机服务器监控面板")
    print(f"  ─────────────────────────────")
    print(f"  Dashboard: http://{host}:{port}/")
    print(f"  JSON API:  http://{host}:{port}/api/stats")
    print(f"  ─────────────────────────────")
    
    app = create_app()
    app.run(host=host, port=port, debug=False, use_reloader=False)


if __name__ == "__main__":
    main()
