# ENGINE 电脑端引擎开启使用指南（engine）

在电脑上启动引擎非常简单，因为它就是一个 Python 写的 FastAPI 本地服务。

## 推荐方式（M15 起）：桌面控制面板一键启动

如果已经有 `desktop\dist\BrowseGuardEngine.exe`，**不需要开终端、不需要激活虚拟环境**：
双击 exe → 在「引擎设置」标签点「启动」→ 窗口直接显示手机要填的局域网地址。
点窗口 X 是隐藏到托盘（引擎继续跑），只有托盘菜单里的「退出」才会彻底停掉引擎。

面板还带「环境依赖自检」：FFmpeg 缺失时点「浏览…」直接选本机 `ffmpeg.exe`，**不必改系统 PATH**；
Playwright Chromium 缺失时点「一键安装内核」并实时看日志。M16 起的「下载控制台」标签
还能在电脑上直接完成嗅探与下载，不依赖手机。

> **信任边界**：引擎无入站鉴权，默认监听 `0.0.0.0` 只为让手机连得上；仅在可信的家庭局域网使用，
> 切勿暴露公网。桌面端的完整说明、配置字段、打包与验收命令见 `desktop/README.md`。

下面的命令行四步保留给「没有 GUI / 需要手工排障 / 跑引擎单测」的场景。

---

比如你的项目路径是 `E:\项目目录\...`，我默认你使用的是 Windows 系统。请按以下 4 步操作：

### 第一步：打开终端

按下键盘上的 `Win + R` 键，输入 `powershell`（或者 `cmd`），然后按回车。

### 第二步：进入引擎目录

在终端里输入以下命令，进入你电脑上的引擎文件夹（请根据你实际的文件夹名调整）：

```powershell
cd E:\项目目录\engine
```

*(注：记得把项目目录换成真实的项目目录路径)*

### 第三步：激活虚拟环境

引擎依赖的 Python 包（比如 yt-dlp、Playwright、FastAPI）都装在 `.venv` 文件夹里，必须先激活它：

```powershell
.venv\Scripts\activate
```

**成功标志**：执行后，你的命令行最前面会出现 `(.venv)` 的字样。

### 第四步：启动引擎

**选项 A（仅在电脑本机/模拟器使用）：**
直接运行：

```powershell
uvicorn main:app --host 127.0.0.1 --port 8730
```

或者

```powershell
python main.py
```

**选项 B（如果你要给物理真机连，或者上一轮要求改成了 0.0.0.0）：**

```powershell
uvicorn main:app --host 0.0.0.0 --port 8730
```

**问题一：重新安装一下依赖**

如果招式一报错说 `No module named uvicorn`，说明你的依赖之前没装全（或者那个 `.venv` 里是空的）。请直接运行：

powershell

pip install -r requirements-lock.txt

等它下载安装完，再执行上面的启动命令。

**问题二：通过 Python 模块启动（最推荐）**

既然虚拟环境对了，依赖也配置了，`uvicorn` 这个库肯定在里面，只是系统没找到它。请直接复制下面这行命令运行：

powershell

python -m uvicorn main:app --host 0.0.0.0 --port 8730

_(注意：把 `uvicorn` 换成 `python -m uvicorn`，这告诉 Python 直接去库里找它)_

---

### 🎉 启动成功的标志

当你看到终端里输出类似下面的内容时，就说明引擎跑起来了：

```text
INFO:     Started server process [12345]
INFO:     Waiting for application startup.
INFO:     Application startup complete.
INFO:     Uvicorn running on http://127.0.0.1:8730 (Press CTRL+C to quit)
```

### ⚠️ 注意事项

1. **千万别关终端**：这个黑窗口不能关，关了引擎就停了。如果你要停止引擎，在这个窗口里按 `Ctrl + C`。
2. **Playwright 浏览器内核**：如果你之前测试时没安装过浏览器内核，第一次嗅探网页可能会报错。如果报错，请在同一个终端里运行 `playwright install chromium` 补装一下即可。
3. **防火墙弹窗**：如果你选了 `0.0.0.0`（选项 B），Windows 可能会弹出一个防火墙警告。**一定要点击“允许访问”**，否则你的手机连不上电脑。

启动后，你就可以去手机 App 的设置里点击「测试连接」了！



---

### 疑问：为什么用 `0.0.0.0`？

在计算机网络里：

* `127.0.0.1` 意思是**“只听我自己说话”**。如果你只用模拟器，因为模拟器和电脑算“同一台机器”，用这个就行。

* `0.0.0.0` 意思是**“听所有网卡说话”**。你的电脑可能连着 WiFi、插着网线、还有虚拟网卡。用 `0.0.0.0` 就代表：“不管手机从哪个网络找我，只要在同一个 WiFi 下，我都接受它的连接。”
  
  ***

### 真机连不上，通常是这“三座大山”在作怪

别急，我们用排除法，一步一步来。**核心判断标准：用手机的浏览器访问引擎。**

#### 第一关：网络通不通？（看 IP 对不对）

1. 电脑上按 `Win + R`，输入 `cmd` 回车。

2. 输入 `ipconfig`，找到你当前连着的 WiFi 的 **IPv4 地址**（比如 `192.168.1.105` 或 `192.168.31.20`）。

3. 确保你的手机连的是**同一个 WiFi**（千万别一个连 WiFi，一个连热点）。

4. **测试**：在手机自带的浏览器里（比如 Chrome/Safari/系统浏览器），输入 `http://你的电脑IP:8730/v1/health`。
   
   * **如果浏览器显示 `{"status":"ok",...}`**：说明网络通了，是 App 的安全配置拦截了（看第三关）。
   
   * **如果浏览器显示“无法访问”**：说明被电脑的防火墙拦住了（看第二关）。

#### 第二关：Windows 防火墙（最常见的原因）

当引擎启动后，Windows 默认会拦截外部设备访问电脑的 8730 端口。

1. 在电脑上，打开控制面板或搜索“Windows Defender 防火墙”。

2. 点击左侧「高级设置」 -> 「入站规则」。

3. 点击右侧「新建规则」 -> 选择「端口」 -> 下一步。

4. 选择「TCP」，特定本地端口填 `8730` -> 下一步。

5. 选择「允许连接」 -> 一路下一步，随便起个名字（比如 BGV Engine），保存。

6. 再回到手机浏览器刷新刚才那个网址，看看能不能打开。

#### 第三关：Android 的“明文网络”安全拦截（App 专属坑）

**这一关现在通常不需要你动手**——包里已经放开了明文：内置浏览器要打开你自己输入的任意站点，
而不少视频站与媒体 CDN 仍是 HTTP。所以先看一眼现场，别急着改代码。

```powershell
# 看仓库里实际打出来的配置（应该是 cleartextTrafficPermitted="true"）
Get-Content E:\code\android\TracelessGlimpse\android\app\src\main\res\xml\network_security_config.xml
```

- **读出来是 `true`** → 不用改任何东西。这时 App 还连不上，问题在地址本身：
  按下面的「额外提醒」检查 `http://` 前缀与局域网 IP，再回第二关确认电脑端监听的是 `0.0.0.0`。
- **读出来是白名单形态**（只有 `127.0.0.1` / `localhost`，说明你手里是很旧的包）→ 才需要放开：

  ```xml
  <?xml version="1.0" encoding="utf-8"?>
  <network-security-config>
      <base-config cleartextTrafficPermitted="true">
          <trust-anchors>
              <certificates src="system" />
          </trust-anchors>
      </base-config>
  </network-security-config>
  ```

  **`<trust-anchors>` 那段别删**：只留 `cleartextTrafficPermitted="true"` 会把系统信任锚一起改掉，
  结果是 HTTPS 请求不再认正规证书，连局域网 TLS 引擎也一起连不上。改完**重新打包安装一次 APK**。

这条放开的代价与边界都写在配置文件顶部的注释里（地址栏对 `http://` 页面显示「不安全」、
下载器只访问你点选的那条 URL），发版前照 `release-checklist.md` §4 复核这三条边界是否仍成立。

* * *

**额外提醒**：  
你在 App 设置页填写的地址，**一定要带上 `http://`**，并且填的是电脑的局域网 IP，比如：  
`http://192.168.1.105:8730`  
（别填 `http://0.0.0.0:8730`，0.0.0.0 只是电脑自己的监听状态，手机不认这个地址）。
