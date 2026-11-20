using System.Windows;
using System.Windows.Controls;
using BnbLauncher.Models;

namespace BnbLauncher;

public partial class SettingsWindow : Window
{
    public SettingsWindow(AppConfig config)
    {
        InitializeComponent();

        OwnerBox.Text = config.Owner;
        RepoBox.Text = config.Repo;
        BranchBox.Text = config.Branch;
        AutoCheckBox.IsChecked = config.AutoCheckUpdates;
        AutoLauncherCheckBox.IsChecked = config.AutoCheckLauncher;
        AutoStartBox.IsChecked = config.AutoStartServer;
        ProxyBox.Text = config.Proxy;

        foreach (var item in VendorBox.Items.OfType<ComboBoxItem>())
        {
            if (string.Equals(item.Tag as string, config.JdkVendor, StringComparison.OrdinalIgnoreCase))
            {
                VendorBox.SelectedItem = item;
                break;
            }
        }

        if (VendorBox.SelectedItem is null) VendorBox.SelectedIndex = 0;

        // 未知/旧值（手改配置写了别的 id）统一落到第一项「直连」
        var mirror = GitMirror.Normalize(config.Mirror);
        foreach (var item in MirrorBox.Items.OfType<ComboBoxItem>())
        {
            if (string.Equals(item.Tag as string, mirror, StringComparison.OrdinalIgnoreCase))
            {
                MirrorBox.SelectedItem = item;
                break;
            }
        }

        if (MirrorBox.SelectedItem is null) MirrorBox.SelectedIndex = 0;
    }

    public AppConfig? Result { get; private set; }

    private void OnCancelClick(object sender, RoutedEventArgs e) => DialogResult = false;

    private void OnSaveClick(object sender, RoutedEventArgs e)
    {
        var owner = OwnerBox.Text.Trim();
        var repo = RepoBox.Text.Trim();
        var branch = BranchBox.Text.Trim();

        if (owner.Length == 0 || repo.Length == 0 || branch.Length == 0)
        {
            MessageBox.Show(this, "Owner, Repo and Branch cannot be empty.", "Settings", MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }

        var vendor = (VendorBox.SelectedItem as ComboBoxItem)?.Tag as string ?? "Corretto";
        var mirror = (MirrorBox.SelectedItem as ComboBoxItem)?.Tag as string ?? GitMirror.DirectId;
        var proxy = ProxyBox.Text.Trim();

        if (proxy.Length > 0
            && !proxy.Contains("://", StringComparison.Ordinal))
        {
            proxy = "http://" + proxy;
        }

        Result = new AppConfig
        {
            Owner = owner,
            Repo = repo,
            Branch = branch,
            JdkVendor = vendor,
            Mirror = GitMirror.Normalize(mirror),
            AutoCheckUpdates = AutoCheckBox.IsChecked == true,
            AutoCheckLauncher = AutoLauncherCheckBox.IsChecked == true,
            AutoStartServer = AutoStartBox.IsChecked == true,
            Proxy = proxy,
        };

        DialogResult = true;
    }
}
