"""桌面端统一视觉主题 —— 配色 / 圆角 / 阴影 / 悬浮动画的单一事实来源。

设计语言（2026-11 美化轮）：
- **现代极简、深色优先**：低饱和深底 + 单一强调色（``ACCENT`` 蓝），
  其余全部中性灰阶，状态色（绿/红/琥珀）只用于语义指示，不当装饰；
- **圆角统一 12px**（``RADIUS``）：卡片 / 按钮 / 输入框 / 状态条一律 12，
  细线条组件（进度条）例外用小圆角，视觉上仍属同族；
- **柔和阴影**：Tk 没有真高斯模糊，卡片用两段 1–2px 渐变条在卡片下缘外推
  （颜色 ``SHADOW_A/B``），按钮用「上浮露影」——按钮抬起来之后露出底下的
  影条。都不引入新依赖；
- **悬浮过渡 200ms**：CTk 自带的 hover 变色是瞬时的，本模块用 ``after``
  分 8 帧做颜色内插（``attach_hover``）；主按钮（``lift_button``）额外做
  2–3px **真位移**：按钮 ``place`` 在等宽 wrapper 里动画 y 偏移，不扰动
  外层 pack/grid 布局。

gui_app / download_console 只允许引用这里的常量与工厂，不散落魔法数。
"""
from __future__ import annotations

import customtkinter as ctk

# -- 几何 -------------------------------------------------------------------
#: 组件统一圆角；进度条等细线条组件自行用小圆角。
RADIUS = 12
#: 悬浮过渡总时长（毫秒）与分帧数。200ms / 8 帧 = 25ms 一帧，肉眼平滑。
HOVER_MS = 200
HOVER_STEPS = 8
#: 窗口：横向宽幅（旧 760x850 接近正方形，用户嫌「太方」）。712 高是按
#: 「控制台页结果区展开 + 一条任务行」实测不裁底定的。
WINDOW_GEOMETRY = "1000x728"
WINDOW_MIN = (920, 640)

# -- 配色（浅色, 深色）元组，跟随 CTk appearance mode -------------------------
#: 窗口 / 标签页底
BG = ("#EEF1F5", "#0F1218")
#: 卡片
SURFACE = ("#FFFFFF", "#171C24")
#: 次级表面：任务行 / 状态条 / 输入框内底
SURFACE_2 = ("#F1F4F8", "#1E242E")
BORDER = ("#E2E6EC", "#272E39")
TEXT_MUTED = ("#6B7280", "#8A93A0")

# 单一强调色（蓝）。hover 提亮而非压暗——深色 UI 上「变亮 = 浮起」。
ACCENT = "#1F6FEB"
ACCENT_HOVER = "#3B85F0"
DANGER = "#E5484D"
DANGER_HOVER = "#EF6B6F"
SUCCESS = "#30A46C"

# 语义提示底（安全警告 / 离线提示），克制版琥珀与红。
WARN_BG = ("#FFF6DE", "#33290F")
WARN_TEXT = ("#8A6400", "#F5C84B")
OFFLINE_BG = ("#FDEBE9", "#33201E")
OFFLINE_TEXT = ("#C0372F", "#FF8A80")

# 按钮体系。次级按钮：浅色模式必须保持「中灰底 + 白字」（浅灰底 + 白字
# 会隐身——浅色截图复核抓到过），深色模式用略亮的表面色。
BTN_DISABLED = ("#C7CCD3", "#333A45")
BTN_DISABLED_TEXT = ("#8A9099", "#6F7783")
BTN_SECONDARY = ("#77808C", "#2A3140")
BTN_SECONDARY_HOVER = ("#646D79", "#39414F")

# 影：卡片下缘渐变条两段（由近及远淡出），与按钮「上浮露影」的影条。
SHADOW_A = ("#CBD3DC", "#0A0D13")
SHADOW_B = ("#DFE4EA", "#0D1118")

# -- 颜色工具 ---------------------------------------------------------------


def resolve(color) -> str:
    """(浅, 深) 元组 → 按 CTk 当前外观模式取单色；单色原样返回。"""
    if isinstance(color, str):
        return color
    idx = 1 if str(ctk.get_appearance_mode()).lower() == "dark" else 0
    return color[idx] if idx < len(color) else color[0]


def _parse(color: str) -> tuple[float, float, float]:
    color = color.lstrip("#")
    return (float(int(color[0:2], 16)), float(int(color[2:4], 16)), float(int(color[4:6], 16)))


def lerp(c1: str, c2: str, t: float) -> str:
    """两个 ``#RRGGBB`` 线性内插，t=0 返回 c1、t=1 返回 c2。"""
    a, b = _parse(c1), _parse(c2)
    mix = tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))
    return "#{:02X}{:02X}{:02X}".format(*mix)


# -- 悬浮动画 ----------------------------------------------------------------


def attach_hover(button, base, target, *, enabled=None, wrap=None,
                 lift_px: int = 0, ms: int = HOVER_MS) -> None:
    """给按钮挂 200ms 悬浮动画（颜色内插；``wrap`` 给出时附加真上浮）。

    - 先把 CTk 自带的 ``hover_color`` 设成底色（它自带变色是瞬时的，
      会让分帧动画看起来抖两下）；
    - ``enabled`` 为 None 时永远可动画；传谓词（如「state != disabled」）
      时，置灰按钮不动画——禁用态的配色由 ``_paint_button`` 一类逻辑接管；
    - 动画每步都查 ``winfo_exists``，控件销毁后不再排帧。
    """
    try:
        button.configure(hover_color=base)
    except Exception:  # noqa: BLE001 —— 非标准按钮容器也能容忍
        pass

    fx: dict = {"after": None, "t": 0.0, "dir": 0}
    step_ms = max(1, ms // HOVER_STEPS)

    def paint(t: float) -> None:
        button.configure(fg_color=lerp(resolve(base), resolve(target), t))
        if wrap is not None and lift_px > 0:
            button.place(x=0, y=max(0, round(lift_px * (1 - t))), relwidth=1.0)

    def run() -> None:
        fx["after"] = None
        try:
            if not button.winfo_exists():
                return
        except Exception:  # noqa: BLE001
            return
        fx["t"] = min(1.0, max(0.0, fx["t"] + fx["dir"] / HOVER_STEPS))
        paint(fx["t"])
        moving = (fx["dir"] == 1 and fx["t"] < 1.0) or (fx["dir"] == -1 and fx["t"] > 0.0)
        if moving:
            fx["after"] = button.after(step_ms, run)

    def cancel() -> None:
        if fx["after"] is not None:
            try:
                button.after_cancel(fx["after"])
            except Exception:  # noqa: BLE001
                pass
            fx["after"] = None

    def go(direction: int) -> None:
        if enabled is not None and not enabled():
            # 置灰期间任何动画都停：配色归 _paint_button / _set_button 管。
            cancel()
            if direction == -1:
                fx["t"], fx["dir"] = 0.0, 0  # 离场即复位，不留在半途颜色
            return
        fx["dir"] = direction
        if fx["after"] is None:
            fx["after"] = button.after(step_ms, run)

    button.bind("<Enter>", lambda _e: go(1), add="+")
    button.bind("<Leave>", lambda _e: go(-1), add="+")


def lift_button(parent, *, base, hover, lift_px: int = 3, enabled=None,
                **btn_kwargs) -> tuple:
    """主按钮工厂：返回 ``(wrapper, button)``。

    wrapper 是与按钮同宽、高 ``h + lift_px`` 的透明容器；按钮 ``place``
    在 y=lift_px 处（静止），悬浮时动画抬到 y=0，露出底下预置的影条——
    「轻微上浮 + 柔和阴影」一次到位，且不扰动外层 pack/grid 布局。
    调用方把 ``wrapper`` pack/grid 到原位置即可，事件与状态仍全部走
    ``button``（invoke / configure / 引用不变）。
    """
    w = int(btn_kwargs.get("width", 120))
    h = int(btn_kwargs.get("height", 36))
    btn_kwargs.setdefault("corner_radius", RADIUS)
    wrap = ctk.CTkFrame(parent, width=w, height=h + lift_px, fg_color="transparent")
    wrap.pack_propagate(False)
    # 影条在按钮下层：静止时被按钮盖住，上浮后露出。颜色直接给 (浅, 深)
    # 元组，让 CTk 在外观模式切换时自行重解析——创建期 resolve 单色会在
    # 「浅色启动 → 深色运行」时留下一条亮线（真机截图复核抓到过）。
    strip = ctk.CTkFrame(wrap, width=max(10, w - 12), height=2,
                         fg_color=SHADOW_A, corner_radius=1)
    strip.place(x=6, y=h + lift_px - 2)
    button = ctk.CTkButton(wrap, **btn_kwargs)
    button.place(x=0, y=lift_px, relwidth=1.0)
    attach_hover(button, base, hover, enabled=enabled, wrap=wrap, lift_px=lift_px)
    return wrap, button


# -- 卡片阴影 ----------------------------------------------------------------


def soft_shadow(card) -> None:
    """在卡片下缘外推两段渐变条，模拟 3px 柔影（先短后长的淡出）。

    必须在卡片 ``pack`` 之后调用：影条按 ``after=card`` 插队，不改变
    既有 pack 顺序。动态显示的卡片（如嗅探结果区）不挂影，只保留描边。
    """
    master = card.master
    # 颜色直接给 (浅, 深) 元组交给 CTk 自管外观切换；创建期 resolve 单色
    # 会在切深色时留下亮线（真机截图复核抓到过）。
    a = ctk.CTkFrame(master, height=2, fg_color=SHADOW_A, corner_radius=2)
    a.pack(fill="x", padx=(12, 12), after=card)
    b = ctk.CTkFrame(master, height=1, fg_color=SHADOW_B, corner_radius=1)
    b.pack(fill="x", padx=(22, 22), after=card)
