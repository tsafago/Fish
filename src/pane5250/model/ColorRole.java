package pane5250.model;

/** Every editable color belongs to a semantic role, including host and effect ink. */
public enum ColorRole {
    BACKGROUND("Surfaces"), PANEL("Surfaces"), FIELD("Surfaces"), DIALOG("Surfaces"),
    TEXT("Controls"), MUTED("Controls"), DISABLED("Controls"), LINE("Controls"),
    HOVER("Controls"), PRESSED("Controls"), FOCUS("Controls"), ERROR("Controls"),
    SELECTION("Controls"), SELECTION_TEXT("Controls"),
    CHROME("Chrome"), CHROME_TEXT("Chrome"), CHROME_INACTIVE("Chrome"),
    MENU("Menus"), MENU_TEXT("Menus"), MENU_HOVER("Menus"), MENU_SELECTED_TEXT("Menus"),
    TOOLTIP("Menus"), TOOLTIP_TEXT("Menus"),
    HEADER("Panes"), HEADER_TEXT("Panes"), HEADER_MUTED("Panes"),
    FOOTER("Panes"), FOOTER_TEXT("Panes"), STATUS_ERROR("Panes"), STATUS_WAIT("Panes"),
    OUTLINE("Panes"), ACTIVE_TINT("Panes"), DIVIDER("Panes"), DIVIDER_BACKGROUND("Panes"),
    CURSOR("Terminal"), TERMINAL_SELECTION("Terminal"),
    HOST_0("Host attributes"), HOST_1("Host attributes"), HOST_2("Host attributes"), HOST_3("Host attributes"),
    HOST_4("Host attributes"), HOST_5("Host attributes"), HOST_6("Host attributes"), HOST_7("Host attributes"),
    HOST_8("Host attributes"), HOST_9("Host attributes"), HOST_10("Host attributes"), HOST_11("Host attributes"),
    HOST_12("Host attributes"), HOST_13("Host attributes"), HOST_14("Host attributes"), HOST_15("Host attributes"),
    GRADIENT_1("Text effects"), GRADIENT_2("Text effects"), GRADIENT_3("Text effects"),
    GRADIENT_4("Text effects"), GRADIENT_5("Text effects"), GRADIENT_6("Text effects"), GLOW("Text effects"),
    LOGO_1("Identity"), LOGO_2("Identity"), LOGO_3("Identity");

    public final String group;
    ColorRole(String group) { this.group=group; }
    @Override public String toString() { return name().replace('_',' ').toLowerCase(java.util.Locale.ROOT); }
}
