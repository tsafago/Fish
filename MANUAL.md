# Fish quick guide

## Getting Started

Ask your administrator for your IBM i address and sign-in method. Use Fish on your organization's trusted network or VPN.

1. Open **Edit > Settings > Profiles**. Choose a profile or click **New**.
2. Enter a name and the host address. Leave **Device** blank unless you were given a device name.
3. Use Windows sign-in if your administrator set it up. For a password login, choose **Password / manual**.
4. Choose the profile next to **+** in the main window, then click **+** to connect.

If Fish can't connect, check the address and your VPN, then click **Reconnect**.

Under **Edit > Settings > General**, **Skip the message queue in-use notice** is on by default. This experimental option presses Enter once per connection for the specific English notice that the message queue is allocated to another job, after the host requests input on that display. Other program messages stay on screen.

Under **Edit > Settings > General > Terminal input**, turn on **Automatically reset local input errors** to keep typing after a rejected key, such as a letter in a numeric-only field, Backspace at the start of a field, or Delete in a protected area. Fish briefly shows a notification instead of a Reset button. A paste stops at the first rejected character: what was already entered stays, and Fish doesn't retry or continue the paste. Host errors and host-busy states aren't cleared. This option is off by default.

If Fish leaves that notice on screen, launch with `--diagnose-startup-notice`. Select the affected pane and open **Host > Startup notice diagnostic...** before pressing Enter. The dialog is safe to copy: it contains the reason, display dimensions, field count and input state, not the terminal text or connection identifiers.

## Using the terminal

Click a pane to type. **Tab** moves between host fields; **Enter** sends your entry. If the keyboard locks after a typing mistake, press **Reset**.

Drag to select text. Right-click for **Copy** and **Paste**. Press **Escape** or click outside the menu to close it.

Open **Help > Keyboard reference** to find a key or change a shortcut.

## Arranging panes

Click **+** to add a pane. Drag its header to the middle of another pane to swap them, or to an edge to put them side by side. The preview shows where the pane will go. Drag a divider to resize. Use **Panes > Undo pane arrangement** to go back.

Use **Ctrl+Tab** to move to the next pane. Closing a pane disconnects its session.

To keep a session running in its own window, right-click the pane and choose **Detach pane** (also under **Panes**), or drag its header out of the window. Drag it onto a pane in another Fish window to dock it. **Escape** cancels a drag. Moving the last pane into another window closes the empty window. Windows opened with a separate data directory cannot exchange sessions.

## Desktop shortcuts

Arrange the panes you want, then open **Settings > Shortcuts** to export this window. Double-click its desktop shortcut to open that arrangement. Return to this tab to rename, replace or delete a shortcut, or choose whether it opens when you sign in to Windows. Keyboard bindings are under **Keyboard Shortcuts**.

## Appearance and updates

In **Settings > Appearance**, choose an app theme. To change terminal text, select a profile and pick a palette or a custom color.

**Settings > Updates** checks for a newer Fish and shows what's changed. Updating closes active sessions. Turn off startup checks here if you prefer to check yourself.
