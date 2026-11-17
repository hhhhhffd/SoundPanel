package dev.soundpanel;

record SoundPanelLayout(int x, int y, int w, int h, boolean compact, int tabY, int tabHeight,
                        int leftX, int leftWidth, int rightX, int rightWidth,
                        int paneY, int paneBottom, int searchY,
                        int listTop, int listBottom, int rowHeight, int visibleRows,
                        int detailY, int buttonY, int buttonHeight, int footerY) {
    static SoundPanelLayout of(int width, int height) {
        int w = Math.min(width - 12, 760), h = Math.min(height - 8, 470);
        int x = (width - w) / 2, y = (height - h) / 2;
        boolean compact = h < 220;
        int leftX = x + 8;
        int inner = w - 16;
        int leftWidth = (inner - 6) * 47 / 100;
        int rightX = leftX + leftWidth + 6;
        int rightWidth = inner - leftWidth - 6;
        int tabY = y + (compact ? 22 : 26), tabH = compact ? 15 : 18;
        int paneY = tabY + tabH + 5, paneBottom = y + h - (compact ? 14 : 18);
        int searchY = paneY + 16;
        int listTop = searchY + (compact ? 17 : 18);
        int buttonHeight = compact ? 15 : 19;
        int buttonY = paneBottom - buttonHeight - 4;
        int detailY = buttonY - (compact ? 13 : 24);
        int listBottom = detailY - 7;
        int rowHeight = h < 330 ? 14 : 17;
        int visible = Math.max(0, (listBottom-listTop)/rowHeight);
        return new SoundPanelLayout(x, y, w, h, compact, tabY, tabH, leftX, leftWidth, rightX, rightWidth,
                paneY, paneBottom, searchY, listTop, listBottom, rowHeight, visible, detailY, buttonY, buttonHeight, y + h - 10);
    }
}
