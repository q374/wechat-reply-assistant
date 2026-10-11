# 本地构建与使用

这份说明面向开发和试用。当前产品是 Android MVP，真实 AI 请求可能产生费用；请使用虚构对象和聊天内容，不要把 API Key 放入源码或日志。

## 1. 准备环境

- Windows
- Android SDK（含 platform-tools、platform 35 和 build-tools 35.0.0）
- JDK 8 兼容环境
- 已开启 USB 调试的 Android 设备

SDK 和 JDK 可以放在项目外的任意本机目录。仓库只保留构建脚本，不包含 SDK、IDE、设备信息或安装包。

## 2. 构建 debug APK

在项目根目录运行：

~~~powershell
powershell -ExecutionPolicy Bypass -File .\reply-assistant\build.ps1 `
  -VersionCode 1 `
  -VersionName 0.1.0
~~~

产物位于 reply-assistant/build/reply-assistant-debug.apk。脚本只生成本地构建产物；build/ 已被忽略，不会进入 Git。

## 3. 构建 release 测试包

release 脚本只从本机被忽略的 .secrets/ 读取签名文件和密码。仓库不提供签名密钥，也不接受把密钥放进源码：

~~~powershell
powershell -ExecutionPolicy Bypass -File .\reply-assistant\build-release.ps1 `
  -VersionCode 1 `
  -VersionName 0.1.0
~~~

## 4. 安装和验证

~~~powershell
adb install -r .\reply-assistant\build\reply-assistant-release.apk
~~~

打开应用后，使用虚构对象和消息完成以下主路径：

1. 打开“微信文字助手”。
2. 选择或创建本机对象。
3. 手动输入或粘贴对方消息。
4. 选择回复类型并生成三条候选。
5. 编辑并复制其中一条。
6. 回到微信检查，最后由用户手动决定是否发送。

当前版本不自动读取微信聊天记录、不自动写入输入框、不自动点击发送按钮。

## 5. 手机直连 DeepSeek

在应用设置中输入 DeepSeek API Key。Key 会通过 Android Keystore 加密保存；首次生成前会提示请求范围，确认后才向服务商发送当前任务内容。删除应用数据或更换设备会影响本机保存的费用估算，平台账单以服务商后台为准。

不要在截图、终端输出、issue、演示录屏或 Git 中展示真实 Key。测试结束后可以在应用设置中移除本机 Key，并按服务商平台规则撤销不再使用的 Key。

## 6. 可选的本地后端联调

手机直连是当前主路径；本地 FastAPI 服务只用于开发联调。启动和接口说明见 [backend/README.md](../backend/README.md)。

连接 USB 后可用：

~~~powershell
adb reverse tcp:8000 tcp:8000
~~~

完成后清理端口转发：

~~~powershell
adb reverse --remove tcp:8000
~~~

## 7. 当前限制

- 当前没有公开发布 APK，也没有多用户账号体系。
- 真实用户完成率、满意度、留存和付费意愿尚未验证。
- 复杂语义事实检查、长中文消息和部分网络故障仍需继续测试。
