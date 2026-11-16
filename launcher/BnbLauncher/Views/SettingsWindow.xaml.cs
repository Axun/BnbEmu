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
        TokenBox.Text = config.GithubToken;
        ProxyBox.Text = config.Proxy;
        FeedBox.Text = config.UpdateFeedUrl;

        foreach (var item in VendorBox.Items.OfType<ComboBoxItem>())
        {
            if (string.Equals(item.Tag as string, config.JdkVendor, StringComparison.OrdinalIgnoreCase))
            {
                VendorBox.SelectedItem = item;
                break;
            }
        }

        if (VendorBox.SelectedItem is null) VendorBox.SelectedIndex = 0;
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
        var proxy = ProxyBox.Text.Trim();
        var feed = FeedBox.Text.Trim();

        if (proxy.Length > 0
            && !proxy.Contains("://", StringComparison.Ordinal))
        {
            proxy = "http://" + proxy;
        }

        if (feed.Length > 0
            && !feed.StartsWith("http://", StringComparison.OrdinalIgnoreCase)
            && !feed.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
        {
            MessageBox.Show(this, "The update feed must be a full http/https URL (latest.json).", "Settings",
                MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }

        Result = new AppConfig
        {
            Owner = owner,
            Repo = repo,
            Branch = branch,
            JdkVendor = vendor,
            AutoCheckUpdates = AutoCheckBox.IsChecked == true,
            AutoCheckLauncher = AutoLauncherCheckBox.IsChecked == true,
            AutoStartServer = AutoStartBox.IsChecked == true,
            GithubToken = TokenBox.Text.Trim(),
            Proxy = proxy,
            UpdateFeedUrl = feed,
        };

        DialogResult = true;
    }
}
