# 网络存储 ROM 加载 - 产品需求文档

## Overview
- **Summary**: 为 Lemuroid 新增网络存储（SMB / FTP / WebDAV）支持，用户配置一次网络共享后，所有设备均可从同一来源加载 ROM，无需逐台拷贝。
- **Purpose**: 用户拥有多台安卓设备，每新增一个游戏都要拷贝到每台设备，效率极低。通过网络共享统一存放 ROM，设备端只缓存封面与临时游玩文件。
- **Target Users**: 拥有多台安卓设备（手机/电视/平板）且 ROM 集中存放在 NAS/PC 共享目录的 Lemuroid 用户。

## Goals
- 支持 SMB、FTP、WebDAV 三种协议连接网络共享
- ROM 同时支持本地目录与网络共享两端加载
- 网络 ROM 默认只下载并显示封面，不下载 ROM 本体
- 用户点击游玩时才将对应 ROM 临时下载到本地缓存并启动模拟器
- 提供网络存储配置界面（协议、主机、端口、账号、密码、远程路径）
- 提供清除网络缓存功能

## Non-Goals
- 不实现多个网络共享账号的同时管理（仅支持单组网络存储配置）
- 不实现网络存储的保存/存档同步（存档仍走本地）
- 不实现网络 ROM 的 CRC 精确匹配（索引阶段不下传 ROM，仅按文件名+扩展名匹配元数据）
- 不实现网络共享内的写操作（只读浏览/下载）

## Background & Context
Lemuroid 现有存储抽象为 `StorageProvider` 接口，已有 `LocalStorageProvider`（file://）和 `StorageAccessFrameworkProvider`（content://）两个实现。索引流程为：`LemuroidLibrary.indexLibrary()` → 遍历所有启用的 provider → `listBaseStorageFiles()` 列出文件 → `getStorageFile()` 解析元数据 → `GameMetadataProvider` 匹配游戏信息 → 入库。启动游戏时调用 `getGameRomFiles()` 返回本地 `File` 列表给模拟器核心。

新增网络存储只需新增一个 `NetworkStorageProvider` 实现，通过 Dagger `@IntoSet` 注入到 provider 集合，并扩展 CoverLoader 支持网络封面。

## Functional Requirements

### FR-1: 网络存储配置
- 应用设置中提供「网络存储」入口，可配置：启用开关、协议类型（SMB/FTP/WebDAV）、主机地址、端口、用户名、密码、远程根路径
- 配置保存到 SharedPreferences
- 提供「测试连接」按钮，验证网络可达与认证有效性

### FR-2: 网络 ROM 索引
- 启用网络存储后，索引流程遍历网络共享目录，递归列出所有文件
- 每个文件生成 `BaseStorageFile`，URI 采用 `smb://` / `ftp://` / `webdav://` scheme
- 索引阶段不下载 ROM 内容（不计算 CRC），仅用文件名+扩展名匹配 libretro DB 元数据
- bin/cue、m3u 多文件游戏按现有合并逻辑处理（通过 `getInputStream` 读取小文件内容）

### FR-3: 网络 ROM 游玩缓存
- 用户点击网络游戏时，`getGameRomFiles()` 将 ROM 下载到应用缓存目录
- 已缓存的 ROM 直接复用，不重复下载
- 多文件游戏（bin/cue）的所有数据文件一并下载到缓存
- 支持 zip 压缩包：下载后按现有逻辑解压出游戏文件
- 缓存目录位于应用 cache 区，用户可在设置中一键清除

### FR-4: 网络封面加载
- 网络 ROM 的封面优先从网络共享同目录查找同名封面图（如 `kof97.zip` → `kof97.jpg`）
- 封面下载到本地封面缓存目录，再交给 Coil 加载显示
- 若无网络封面，回退到在线封面（libretro thumbnails）

### FR-5: 本地与网络共存
- 本地存储与网络存储可同时启用，索引结果合并
- 游戏列表不区分来源，统一展示

## Non-Functional Requirements
- **NFR-1**: 网络索引失败时不阻断本地索引，记录错误日志
- **NFR-2**: ROM 下载过程中向用户展示加载状态（复用现有 LoadingGame 状态）
- **NFR-3**: 兼容 minSdk 21（安卓5）与 minSdk 23（安卓6+）两个构建变体
- **NFR-4**: 网络库依赖从 mavenCentral 拉取（jcenter 已关闭）

## Constraints
- **Technical**: 
  - 必须兼容现有 `StorageProvider` 接口与 `StorageProviderRegistry` 路由机制
  - 模拟器核心只接受本地 `File` 路径，网络 ROM 必须先落地缓存
  - 构建环境：Gradle 7.2, Kotlin 1.6.21, JDK 17（主）+ JDK 11（LibretroDroid）
  - minSdk 21/23 可配置
- **Dependencies**: 
  - SMB: `eu.agno3.jcifs:jcifs-ng`（mavenCentral）
  - FTP: `commons-net:commons-net`（mavenCentral）
  - WebDAV: 复用现有 OkHttp 依赖，手动实现 PROPFIND

## Assumptions
- 用户的网络共享在同一局域网内，延迟与带宽可接受
- 用户通常只有一个 NAS/共享源，单组配置足够
- 网络 ROM 的文件名与本地一致，可按文件名匹配元数据

## Acceptance Criteria

### AC-1: 网络存储配置可保存并持久化
- **Type**: `rule`
- **Given**: 用户在设置中填写了网络存储配置（协议、主机、端口、账号、密码、路径）
- **When**: 用户保存配置并重启应用
- **Then**: 配置仍存在，网络存储状态保持
- **Pass Condition**: 重启后 SharedPreferences 中可读到完整配置
- **Evidence**: 代码审查配置存取逻辑 + 构建通过

### AC-2: 启用网络存储后可索引到网络 ROM
- **Type**: `rule`
- **Given**: 网络存储已正确配置且共享目录存在 ROM 文件
- **When**: 触发重新扫描
- **Then**: 网络共享中的 ROM 出现在游戏列表中
- **Pass Condition**: 索引后数据库中存在 fileUri 为 smb/ftp/webdav scheme 的游戏记录
- **Evidence**: 代码审查 listBaseStorageFiles 实现

### AC-3: 网络 ROM 游玩时下载到本地缓存
- **Type**: `rule`
- **Given**: 游戏列表中存在一个网络 ROM
- **When**: 用户点击该游戏开始游玩
- **Then**: ROM 文件被下载到应用缓存目录，模拟器从本地缓存加载
- **Pass Condition**: getGameRomFiles 返回的 File 位于应用 cache 目录且内容完整
- **Evidence**: 代码审查 getGameRomFiles 下载逻辑

### AC-4: 已缓存 ROM 不重复下载
- **Type**: `rule`
- **Given**: 某网络 ROM 已下载到本地缓存
- **When**: 用户再次点击该游戏
- **Then**: 直接使用缓存文件，不发起网络下载
- **Pass Condition**: 缓存文件存在时直接返回，不调用网络下载
- **Evidence**: 代码审查缓存命中逻辑

### AC-5: 网络封面可加载显示
- **Type**: `rule`
- **Given**: 网络共享中 ROM 同目录存在同名封面图
- **When**: 游戏列表展示该游戏
- **Then**: 封面图被下载并显示
- **Pass Condition**: CoverLoader 对 smb/ftp/webdav scheme 返回封面 File
- **Evidence**: 代码审查 CoverLoader 网络分支

### AC-6: 本地与网络 ROM 可同时展示
- **Type**: `rule`
- **Given**: 本地目录和网络共享中各有 ROM
- **When**: 完成索引
- **Then**: 游戏列表同时包含本地与网络游戏
- **Pass Condition**: 数据库中同时存在 file:// 和 smb/ftp/webdav scheme 的记录
- **Evidence**: 代码审查 provider 注册与索引遍历

### AC-7: 网络索引失败不影响本地
- **Type**: `rule`
- **Given**: 网络存储配置错误或网络不可达
- **When**: 触发索引
- **Then**: 索引流程记录错误但继续完成本地 ROM 索引
- **Pass Condition**: indexProviders 中单个 provider 异常被捕获，不中断整体
- **Evidence**: 代码审查异常处理

### AC-8: 可清除网络缓存
- **Type**: `rule`
- **Given**: 应用缓存目录中存在网络下载的 ROM
- **When**: 用户点击「清除网络缓存」
- **Then**: 网络 ROM 缓存文件被删除
- **Pass Condition**: 缓存目录下网络子目录被清空
- **Evidence**: 代码审查清除逻辑

### AC-9: 三端构建均通过
- **Type**: `rubric`
- **Dimension**: 构建稳定性
- **Scale**: 1-5
- **Anchors**: 1 = 编译失败；3 = 单变体通过；5 = android5(21) 与 android6+(23) 两个 APK 均 BUILD SUCCESSFUL
- **Pass Threshold**: >= 5
- **Evidence**: `./gradlew assembleFreeBundleRelease` 与 `-Plemuroid.minSdk=21` 两次构建输出

## Open Questions
- 无（设计采用单组配置，覆盖主流单 NAS 场景）
