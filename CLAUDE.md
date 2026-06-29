# yousa.ccwu.cc — 环境配置总览

## 项目结构

```
D:\DeepSeek\mysite\          ← 本地开发目录（Git 仓库）
├── app.py                    ← Flask 主应用（所有路由）
├── templates/                ← Jinja2 模板
├── static/                   ← 静态文件（CSS/JS/图片/APK直链）
├── wiki/                     ← 知识库 Markdown 文件
│   ├── 欢迎.md
│   ├── concepts/
│   ├── comparisons/
│   └── entities/
├── files/                    ← 文件下载目录
├── requirements.txt          ← Flask, Flask-Login, Markdown, Werkzeug
├── deploy.sh                 ← 服务器一键部署脚本
└── deploy-local.sh           ← 本地打包 → Workbench 部署

D:\DeepSeek\yousa-android\    ← 安卓 App 项目（无 Git）
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/yousa/app/
│       │   ├── MainActivity.java
│       │   ├── UpdateChecker.java
│       │   └── ApkDownloadReceiver.java
│       └── res/
├── build.gradle              ← 根构建文件
├── gradle.properties
├── settings.gradle
└── local.properties          ← sdk.dir=D:\\AndroidBuildEnv\\android-sdk
```

## 网站架构

### 技术栈
- **框架**: Python Flask + Flask-Login
- **数据库**: SQLite（users.db）
- **Web 服务器**: Nginx 反向代理 → Flask(:5000)
- **域名**: yousa.ccwu.cc（Cloudflare 托管，DNS 通过 Cloudflare）
- **源代码管理**: GitHub YOUSA-CECE/yousa.ccwu.cc（公开仓库）

### 本地开发环境 (Windows)
- **Python**: 3.11.15 (通过 pip)
- **依赖**: Flask, Flask-Login, Markdown, Werkzeug
- **WebView 预览**: 无需额外工具，直接运行 app.py 后访问 localhost:5000

### 服务器环境 (Alibaba Cloud ECS)
- **系统**: Alibaba Cloud Linux 3.2104 LTS（包管理器: dnf/yum，非 apt）
- **部署路径**: `/opt/yousa/`
- **进程管理**: systemd 服务 yousa.service
- **启动命令**: `/usr/bin/python3 /opt/yousa/app.py`
- **端口**: 5000（Flask），443/80（Nginx）
- **更新脚本**: 服务器上没有 update.sh，部署方式是通过 Workbench 终端手动操作
  ```bash
  # 部署步骤：
  cd /opt/yousa && git pull
  rm -rf __pycache__
  systemctl restart yousa
  sleep 2
  curl -s -o /dev/null -w "%{http_code}" https://yousa.ccwu.cc/app
  ```

### Nginx
- 两个配置文件可能存在冲突：
  - `/etc/nginx/conf.d/` (原始)
  - `/www/server/panel/vhost/nginx/` (宝塔面板)
- 证书覆盖问题已通过删除冲突配置解决
- 修改后需 `nginx -s reload`

### 用户系统
| 角色 | 用户名 | 密码 |
|------|--------|------|
| 管理员 | admin | admin123 |
| 普通用户 | user | user123 |
| 游客 | 无需登录 | - |

### 应用路由
| 路由 | 功能 |
|------|------|
| `/` | 首页 |
| `/login` | 登录 |
| `/register` | 注册 |
| `/logout` | 退出 |
| `/admin` | 后台管理 |
| `/profile` | 个人信息 |
| `/app`, `/download` | 安卓 App 下载页 |
| `/about` | 关于 |
| `/search` | 搜索 |
| `/wiki/`, `/wiki/<path>` | 知识库浏览 |
| `/wiki/search` | 知识库搜索 |
| `/chat` | AI 聊天 |
| `/chat/api` | 聊天 API（POST） |
| `/files/`, `/files/<path>` | 文件下载 |
| `/blog` | 博客列表 |
| `/blog/write` | 写博客 |
| `/blog/<int:id>` | 博客详情 |
| `/blog/<int:id>/delete` | 删除博客 |
| `/guestbook` | 留言板 |
| `/guestbook/<int:id>/delete` | 删除留言 |

### 知识库管理 (通过浏览器)
- **上传**: POST `/admin/wiki/upload` (multipart/form-data: file, title, category)
  - 注意：使用 fetch 会因重定向失败，使用 XHR（browser_console 执行）
- **删除**: POST `/admin/wiki/delete/{id}`
- **存储**: `/opt/yousa/wiki/` 目录下的 .md 文件

### Flask 开发注意事项
1. `@login_required` 装饰器必须在所有 `@app.route` 的最后（否则 Flask-Login 报错）
2. chat.html 前端通过 `chatHistory` 数组 + `/history` 端点实现对话上下文记忆（最多 30 轮）
3. 本地修改后杀旧进程：`taskkill //F //IM python.exe`（注意用双斜杠）

## 安卓 App

### 技术栈
- **语言**: Java（纯 Android SDK，无第三方依赖）
- **编译**: Gradle 8.7 + Android SDK 35 + JDK 17
- **最低 SDK**: 24
- **目标 SDK**: 35
- **包名**: com.yousa.app
- **应用名**: yousa

### 开发环境路径
```
D:\AndroidBuildEnv\
├── jdk\jdk-17.0.19+10\
├── android-sdk\           ← API 35
├── gradle\gradle-8.7\
└── gradle-cache\
```

### 编译命令
```bash
export JAVA_HOME=/d/AndroidBuildEnv/jdk/jdk-17.0.19+10
export ANDROID_HOME=/d/AndroidBuildEnv/android-sdk
export PATH=$JAVA_HOME/bin:/d/AndroidBuildEnv/gradle/gradle-8.7/bin:$PATH

cd /d/DeepSeek/yousa-android && gradle assembleDebug --no-daemon 2>&1 | tail -5
```

### 编译陷阱
- **Clash Verge（verge-mihomo）** 在 `127.0.0.1:7897` 劫持 DNS → 编译时需关闭代理，否则无法访问 Google Maven
- **APK 输出**: `app/build/outputs/apk/debug/app-debug.apk`
- **部署**: 复制到 `D:\DeepSeek\mysite\static\` 作为网站 APK 直链

### App 功能
- WebView 壳应用，加载 `https://yousa.ccwu.cc`
- 支持 JavaScript、DOM Storage、双指缩放
- 下拉刷新（touch 手势）
- 后台自动检查更新（通过 `/api/check-update`）
- 更新弹窗 + 下载安装
- 状态栏颜色: #EEF3F8（浅灰），亮色图标
- 物理返回键：WebView 回退

### APK 版本信息
- 当前版本: v1.0.0
- 文件名: `yousa-安卓App_v1.0.0.apk`
- 网站直链: `/static/yousa-安卓App_v1.0.0.apk`
- 下载页面: `/app` 和 `/download`

## SSH 连接（阿里云 Workbench）
- 端口 22 被阿里云 aegis 安全组件拦截，无法直接 SSH
- 访问方式：阿里云 ECS 控制台 → Workbench 远程连接
- Git 推送：本地 push → Workbench 终端内 `git pull`
- GitHub SSH 绕 WARP：`ssh.github.com:443`
- 服务器主机名: `iZj6c4d5i8cww32fdjzv6gZ`

## 网站更新流程
1. 本地开发 → git commit → git push
2. 打开 Workbench 终端
3. `cd /opt/yousa && git pull`
4. `rm -rf __pycache__`
5. `systemctl restart yousa`
6. 验证：`curl -s -o /dev/null -w "%{http_code}" https://yousa.ccwu.cc/app`

## 其他
- 所有 AI 生成图片存放: `D:\DeepSeek\生成图片\`
- 文件名格式: `描述_日期.png`
- 服务器上 `/opt/yousa/` 不是 Git 仓库（本地打包 base64 → Workbench 粘贴 tar czf 部署的方式已弃用，改用 Git pull）
