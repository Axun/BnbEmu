using System.Text.Json.Serialization;

namespace BnbLauncher.Models;

/// <summary>Azul Metadata API 返回的包信息（只取需要的字段）。</summary>
public sealed class AzulPackageDto
{
    [JsonPropertyName("download_url")] public string? DownloadUrl { get; set; }
    [JsonPropertyName("name")] public string? Name { get; set; }
    [JsonPropertyName("java_version")] public int[]? JavaVersion { get; set; }
}
