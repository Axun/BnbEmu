using System;
using System.Globalization;
using BnbLauncher.Models;

namespace BnbLauncher.Services;

/// <summary>
/// 系统语言判断，以及跟着语言走的一次性提示。
///
/// 为什么按「系统语言」而不是配置文件语言：启动器的界面文案是英文的，给中文用户
/// 主动解释「GitHub 慢可以换镜像」这件事只能靠系统语言猜，猜错了最多多一行日志。
/// 判据用 CurrentUICulture（Windows 的「显示语言」），不是 CurrentCulture（数字/日期格式）：
/// 英文系统 + 中文区域格式的用户看得懂英文界面，不需要这条中文提示。
/// </summary>
public static class SystemLocale
{
    /// <summary>简体中文：zh-CN / zh-SG / zh-CHS（旧名）/ zh-Hans*。</summary>
    public static bool IsSimplifiedChinese => IsSimplifiedChineseCulture(CultureInfo.CurrentUICulture);

    private static bool IsSimplifiedChineseCulture(CultureInfo culture)
    {
        var name = culture.Name;

        if (name.Equals("zh-CN", StringComparison.OrdinalIgnoreCase)
            || name.Equals("zh-SG", StringComparison.OrdinalIgnoreCase)
            || name.Equals("zh-CHS", StringComparison.OrdinalIgnoreCase))
            return true;

        if (name.StartsWith("zh-Hans", StringComparison.OrdinalIgnoreCase)) return true;

        // 中性文化 "zh"（个别精简系统只报这个）：按 LCID 兜底
        // 0x0004 zh-Hans / 0x0804 zh-CN / 0x1004 zh-SG；zh-TW 0x0404、zh-HK 0x0C04 是繁体，不算
        if (culture.TwoLetterISOLanguageName.Equals("zh", StringComparison.OrdinalIgnoreCase))
            return culture.LCID is 0x0004 or 0x0804 or 0x1004;

        return false;
    }

    /// <summary>
    /// 需要提示「可以切镜像」的场景，返回提示文案；其余情况返回 null。
    ///
    /// 三个条件同时成立才提示：
    ///   1. 系统显示语言是简体中文 —— 只有这类用户读中文提示最自然；
    ///   2. 没开镜像 —— 已经选过镜像的人不需要再被告知一次；
    ///   3. 没有任何代理可用 —— 设置里的 proxy、环境变量、系统代理、git 全局 http.proxy
    ///      全都没有（GitNetwork 的解析结果就是这条链的最终答案）。
    ///      有系统代理的人多半已经在用梯子，再提示只会变成噪音。
    /// </summary>
    public static string? MirrorTip(AppConfig cfg)
    {
        if (!IsSimplifiedChinese) return null;
        if (GitMirror.IsMirror(cfg.Mirror)) return null;
        if (GitNetwork.ProxyUrl(cfg) is not null) return null;

        return "⚠如果在中国大陆，遇到GitHub访问被干扰下载速度很慢，可在设置里把下载通道切换成镜像站⚠";
    }
}
