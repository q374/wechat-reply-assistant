# 项目结构

仓库只保留用于理解产品、实现和验证的内容：

~~~text
微信回复助手/
├─ reply-assistant/       Android 产品代码和构建脚本
├─ backend/               可选的 FastAPI 本地联调服务
├─ docs/                  产品、技术、评测和开发说明
├─ PRD.md                 产品定位、范围和隐私边界
├─ README.md              项目入口
└─ .gitignore             本机文件和敏感材料排除规则
~~~

以下内容只存在于开发者本机，不进入公开仓库：

- Android SDK、Android Studio、JDK 和下载缓存；
- build/、out/、临时 APK 和签名产物；
- .secrets/、.env、API Key、release keystore 和密码；
- work/ 下的截图、XML、日志、模型原始输出和设备调试证据；
- 视频工程、node_modules 和本地缓存；
- 设备序列号、私有网络地址和个人路径。

如果需要新增演示材料，优先放入 docs/，并先脱敏、压缩范围，再检查 git diff 和 git status。

