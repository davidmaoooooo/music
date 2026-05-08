# 音乐 Music

这是一个基于波尼音乐改造的可共享音频的 Android 音乐播放器版本。

## 功能

- 支持本地音乐播放等基础音乐功能。
- 支持创建/加入一起听房间，两台设备输入同一个房间号即可同步听歌。
- 房主和客人都可以切歌、播放、暂停、拖动进度条、切换歌单，操作会同步到对方设备。
- 一起听模块按需启用：只有进入房间才启动 WebSocket、前台服务和保活逻辑，退出房间后立即释放。
- 支持断线重连、服务器状态回放、房间短期保留和 Debug 日志抓取开关。
- 一起听服务端基于 Cloudflare Workers + Durable Objects，代码位于 `cloudflare/listen-together-worker.js`。若担心数据安全，可自行部署并指定服务器地址。

## 致谢

- 感谢 [波尼音乐 / PonyMusic](https://github.com/wangchenyan/ponymusic) 提供优秀的开源播放器基础，本项目是在其基础上继续学习和改造。
- 感谢 [NeteaseCloudMusicApi](https://github.com/Binaryify/NeteaseCloudMusicApi) 及相关社区项目提供 API 能力。
- 感谢 Android、Media3、ExoPlayer、Cloudflare Workers 等开源生态。

## 开源协议

本项目基于原波尼音乐项目继续改造，沿用 Apache License 2.0 开源协议。

本项目不提供破解、代理、登录服务，请遵守相关平台规则。本项目仅用于个人学习、研究和自用场景，请勿用于商业化或滥用访问。使用者请于24小时内将本项目删除。
