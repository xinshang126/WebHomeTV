# JKTV 的改动边界

以 WebHomeTV 源码 4e30ffaf219b1db15fe5662aeff6f9820e2b32bf 为起点，单独在测试分支修改，不修改上游或主分支。

- 应用与 Gradle 项目名 JKTV，安装 ID `com.jktv.player`。原 Java、反射和 JNI 命名空间保持 `com.fongmi.android.tv`；文件与启动 provider 沿用动态 `${applicationId}`。
- APK 不再打包 MPV 的 ABI 资源，不注册 MPV 播放器。旧 MPV 数字设置降为 Exo；保留 Exo=0、IJK=1，IJK 自动回退不再选择 MPV。兼容常量和旧实现源码暂留在仓库，未声称逐文件清理所有旧代码。
- 移除壳代理规则激活（仅 `app` selector 命名项）与管理入口，不改配置的通用代理、DNS、播放代理。
- 普通直播/点播配置不再执行站点注入；保留普通配置、收藏与当前站点选择。仅禁用专用 WebHome HTML 首页、WebHome 扩展加载和管理页注入/壳代理分区；保留常规 HTTP 服务、解析及 JS/Python/JAR Spider 源路径。不删除原应用或用户数据。
- 原生 TV 首页使用紧凑上栏、深色背景和常规分类焦点路径。未复制 OK 影视反编译代码或资源；视觉/遥控器体验仍需真机确认。

验收：ARM64 APK 用 aapt 确认包名、Leanback 应用入口和签名；确认没有 `assets/mpv-libs/`，IJK 三个库仍打包。安装时不得覆盖其他播放器。完整构建失败需记录具体工具链、依赖或源代码错误，不能把静态审查当成真机通过。
