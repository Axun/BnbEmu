using System.Text;
using System.Windows;
using BnbLauncher.ViewModels;

namespace BnbLauncher;

public partial class CommitWindow : Window
{
    private readonly CommitListPayload _payload;

    public CommitWindow(CommitListPayload payload)
    {
        InitializeComponent();
        _payload = payload;
        DataContext = payload;

        // 有更新时默认看「本次更新」，否则直接展示完整历史；两份列表都不截断。
        SwitchScope(showAll: !payload.ShowScopeSwitch);
    }

    private void OnShowContextClick(object sender, RoutedEventArgs e) => SwitchScope(showAll: false);

    private void OnShowAllClick(object sender, RoutedEventArgs e) => SwitchScope(showAll: true);

    private void SwitchScope(bool showAll)
    {
        if (showAll) _payload.ShowAll();
        else _payload.ShowContext();

        ContextTab.Style = (Style)FindResource(showAll ? "SecondaryButton" : "PrimaryButton");
        AllTab.Style = (Style)FindResource(showAll ? "PrimaryButton" : "SecondaryButton");

        // 换列表后回到顶部，避免停在上一份列表的中间位置
        if (CommitList.HasItems) CommitList.ScrollIntoView(CommitList.Items[0]);
    }

    private void OnCloseClick(object sender, RoutedEventArgs e) => Close();

    private void OnCopyClick(object sender, RoutedEventArgs e)
    {
        var sb = new StringBuilder();
        foreach (var commit in _payload.Commits)
        {
            sb.AppendLine($"{commit.ShortSha}  {commit.Title}  ({commit.Author}, {commit.DateText})");
        }

        try
        {
            Clipboard.SetText(sb.ToString());
        }
        catch
        {
            // 剪贴板被占用时忽略
        }
    }
}
