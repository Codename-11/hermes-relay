$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -ReferencedAssemblies System.Windows.Forms,System.Drawing -TypeDefinition @'
using System;
using System.Windows.Forms;
public class TokenFixture : Form {
    protected override bool ShowWithoutActivation { get { return true; } }
    public TokenFixture() {
        Text = "Relay CUA test fixture";
        Width = 500; Height = 300;
        var input = new TextBox { AccessibleName = "Fixture input", Multiline = true,
            ScrollBars = ScrollBars.Vertical, Left = 20, Top = 20, Width = 420, Height = 150 };
        var button = new Button { Text = "Fixture button", Left = 20, Top = 190, Width = 200 };
        button.Click += (sender, args) => { button.Text = "Fixture clicked"; };
        Controls.Add(input); Controls.Add(button);
    }
}
'@
$form = New-Object TokenFixture
$form.Add_Shown({
    [Console]::WriteLine((@{ pid = $PID; windowId = $form.Handle.ToInt64() } | ConvertTo-Json -Compress))
})
[System.Windows.Forms.Application]::Run($form)
