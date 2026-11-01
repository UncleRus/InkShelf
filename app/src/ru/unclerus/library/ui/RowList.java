package ru.unclerus.library.ui;

/**
 * Строки одной страницы: чередование заголовков разделов и элементов. Элемент
 * ссылается на позицию в оконном курсоре страницы ({@code 0..n-1}), заголовок
 * несёт текст раздела. Если страница начинается посреди группы, вверху
 * добавляется повторный заголовок — страница самодостаточна (макет 21).
 */
public final class RowList {

    public static final int HEADER = 0;
    public static final int ITEM = 1;

    private int[] mType = new int[16];
    private String[] mText = new String[16];
    private int[] mPos = new int[16];
    private int mSize;

    private RowList() {}

    /** Собрать строки страницы {@code [start, end)} по разделам {@code sections}. */
    public static RowList of(int start, int end, Sections sections) {
        RowList r = new RowList();
        if (sections != null && start < sections.total() && !sections.isStart(start)) {
            r.header(sections.letterOf(start));
        }
        for (int i = start; i < end; i++) {
            if (sections != null && sections.isStart(i)) {
                r.header(sections.letterOf(i));
            }
            r.item(i - start);
        }
        return r;
    }

    private void ensure() {
        if (mSize < mType.length) {
            return;
        }
        int cap = mType.length * 2;
        int[] t = new int[cap];
        String[] s = new String[cap];
        int[] p = new int[cap];
        System.arraycopy(mType, 0, t, 0, mSize);
        System.arraycopy(mText, 0, s, 0, mSize);
        System.arraycopy(mPos, 0, p, 0, mSize);
        mType = t;
        mText = s;
        mPos = p;
    }

    private void header(String text) {
        ensure();
        mType[mSize] = HEADER;
        mText[mSize] = text;
        mPos[mSize] = -1;
        mSize++;
    }

    private void item(int cursorPos) {
        ensure();
        mType[mSize] = ITEM;
        mText[mSize] = null;
        mPos[mSize] = cursorPos;
        mSize++;
    }

    public int size() {
        return mSize;
    }

    public int type(int row) {
        return mType[row];
    }

    public boolean isHeader(int row) {
        return mType[row] == HEADER;
    }

    public String text(int row) {
        return mText[row];
    }

    /** Позиция элемента в курсоре страницы (для ITEM). */
    public int position(int row) {
        return mPos[row];
    }
}
