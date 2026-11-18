# Lemuroid
- 基于lemuroid的四叶草修改版,此版本号与原作者发布的版本号刚好相同但不是同一个,请勿搞混
- 1.优化了在旧安卓电视上无法选择目录时就预设了一个/sdcard/roms目录给它
- 2.网络加载不到游戏封面图时可以自定义本地的jpg图为封面比如
     - kof97.zip  kof97.jpg kof97.mp4
     - 后来发现还是太麻烦了.我直接兼容了天马G的目录模式,拿天马G的目录过来可以直接加载封面和视频
- 3.模糊掉按文件夹分类的严格要求,可以随便放哪个目录下
- 4.增强bios直接内置一些常用的bios文件能识别出更多的来自其它模拟器的rom文件
- 5.增加smb ftp webdav方式的roms来源,因为设备比较多每次新增一个游戏都要给每台复制游戏文件太麻烦了,所以直接用网络roms的话维护起来会更方便.
- 6.增加所有游戏版块,节省在不同主机间翻来翻去的麻烦事儿
- 7.增加禁用某类主机的功能,长按某个主机版块就可以禁用.长按也可以重新启用,主要目的是有些电视较为老旧带不动某些主机比如小米电视L43M5 ES带不动3DS主机,防止浪费时间.禁用后的主机里的游戏不会显示在所有游戏版块内就不会选到带不动的游戏了.赞吧
- 8.本地roms和网络roms可以同时混用,自动合并显示一个封面,优先加载本地这份.
- 9.支持自定义手柄设备为自动或1p或2p,因为有两个手柄默认A手柄永远是1p比较麻烦,虽然关掉一个可以临时把B变成1p,但如果双人玩时又自动A抢了1p,所以加了这个功能.当然保持自动预设就和我没来过一样也是可以的.
- 请四叶草作者喝啤酒
<img width="1118" height="1524" alt="donate_qr" src="https://github.com/user-attachments/assets/f5e9574e-42b2-49d7-a14e-9ee9fd683fb6" />


[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
     alt="Get it on F-Droid"
     height="80">](https://f-droid.org/packages/com.swordfish.lemuroid/)
[<img src="https://play.google.com/intl/en_us/badges/images/generic/en-play-badge.png"
     alt="Get it on Google Play"
     height="80">](https://play.google.com/store/apps/details?id=com.swordfish.lemuroid)

## Description

Lemuroid is an open-source emulation project for Android based on Libretro. Its main goal is ease of use, good Android integration and a great user experience.

It originated from a rib of [Retrograde](https://github.com/retrograde/retrograde-android), but graduated to a standalone project integrating [LibretroDroid](https://github.com/Swordfish90/LibretroDroid).

|Screen 1|Screen 2|Screen 3|
|---|---|---|
|![Screen1](https://github.com/Swordfish90/Lemuroid/blob/master/fastlane/metadata/android/en-US/images/phoneScreenshots/1.jpg)|![Screen2](https://github.com/Swordfish90/Lemuroid/blob/master/fastlane/metadata/android/en-US/images/phoneScreenshots/2.jpg)|![Screen3](https://github.com/Swordfish90/Lemuroid/blob/master/fastlane/metadata/android/en-US/images/phoneScreenshots/3.jpg)|

### Supported Systems:
- Atari 2600 (A26) ([stella](https://docs.libretro.com/library/stella/))
- Atari 7800 (A78) ([prosystem](https://docs.libretro.com/library/prosystem/))
- Atari Lynx (Lynx) ([handy](https://docs.libretro.com/library/handy/))
- Nintendo (NES) ([fceumm](https://docs.libretro.com/library/fceumm/))
- Super Nintendo (SNES) ([snes9x](https://docs.libretro.com/library/snes9x/))
- Game Boy (GB) ([gambatte](https://docs.libretro.com/library/gambatte/))
- Game Boy Color (GBC) ([gambatte](https://docs.libretro.com/library/gambatte/))
- Game Boy Advance (GBA) ([mgba](https://docs.libretro.com/library/mgba/))
- Sega Genesis (aka Megadrive) ([genesis_plus_gx](https://docs.libretro.com/library/genesis_plus_gx/))
- Sega CD (aka Mega CD) ([genesis_plus_gx](https://docs.libretro.com/library/genesis_plus_gx/))
- Sega Master System (SMS) ([genesis_plus_gx](https://docs.libretro.com/library/genesis_plus_gx/))
- Sega Game Gear (GG) ([genesis_plus_gx](https://docs.libretro.com/library/genesis_plus_gx/))
- Nintendo 64 (N64) ([mupen64plus](https://docs.libretro.com/library/mupen64plus/))
- PlayStation (PSX) ([PCSX-ReARMed](https://docs.libretro.com/library/pcsx_rearmed/))
- PlayStation Portable (PSP) ([ppsspp](https://docs.libretro.com/library/ppsspp/))
- FinalBurn Neo (Arcade) ([fbneo](https://github.com/libretro/FBNeo/))
- Nintendo DS (NDS) ([desmume](https://docs.libretro.com/library/desmume/)/[MelonDS](https://docs.libretro.com/library/melonds/))
- NEC PC Engine (PCE) ([beetle_pce_fast](https://docs.libretro.com/library/beetle_pce_fast/))
- Neo Geo Pocket (NGP) ([mednafen_ngp](https://docs.libretro.com/library/beetle_neopop/))
- Neo Geo Pocket Color (NGC) ([mednafen_ngp](https://docs.libretro.com/library/beetle_neopop/))
- WonderSwan (WS) ([beetle_cygne](https://docs.libretro.com/library/beetle_cygne/))
- WonderSwan Color (WSC) ([beetle_cygne](https://docs.libretro.com/library/beetle_cygne/))
- Nintendo 3DS (3DS) ([citra](https://docs.libretro.com/library/citra/))

### Features:
- Android TV support
- Automatically save and restore game states.
- ROMs scanning and indexing
- Optimized touch controls
- Quick save/load
- Support for Zipped ROMs
- Display simulation (LCD/CRT)
- Gamepad support
- Local multiplayer
- Tilt input
- Customizable touch controls (size and position)
- Cloud save sync

### Languages:
You can help translate Lemuroid in your native language by going here: https://crowdin.com/project/lemuroid
