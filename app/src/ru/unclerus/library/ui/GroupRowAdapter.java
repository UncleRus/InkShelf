package ru.unclerus.library.ui;

import android.content.Context;
import android.database.Cursor;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import ru.unclerus.library.R;

/**
 * Адаптер строк классификаторов (автор/жанр/серия): иконка, имя, счётчик книг,
 * шеврон. Колонки курсора задаются индексами — DAO классификаторов отдают
 * разные наборы ({@code AuthorDao.COLS_SQL} и т. п.).
 *
 * <p>Строки страницы приходят как {@link RowList}: она чередует заголовки
 * разделов и элементы. Элемент ссылается на позицию в оконном курсоре страницы,
 * поэтому в памяти по-прежнему только видимое окно.
 */
public final class GroupRowAdapter extends BaseAdapter {

    private final LayoutInflater mInf;
    private final Cursor mCursor;
    private final RowList mRows;
    private final int mIcon;
    private final int mNameCol;
    private final int mCountCol;

    public GroupRowAdapter(Context ctx, Cursor cursor, RowList rows, int icon,
                           int nameCol, int countCol) {
        mInf = LayoutInflater.from(ctx);
        mCursor = cursor;
        mRows = rows;
        mIcon = icon;
        mNameCol = nameCol;
        mCountCol = countCol;
    }

    @Override
    public int getCount() {
        if (mRows != null) {
            return mRows.size();
        }
        return mCursor == null ? 0 : mCursor.getCount();
    }

    @Override
    public Object getItem(int position) {
        return null;
    }

    @Override
    public long getItemId(int position) {
        if (isHeader(position)) {
            return -1 - position;
        }
        if (!move(position)) {
            return 0;
        }
        return mCursor.getLong(0);
    }

    @Override
    public boolean hasStableIds() {
        return true;
    }

    @Override
    public int getViewTypeCount() {
        return 2; // 0 — заголовок раздела, 1 — строка группы
    }

    @Override
    public int getItemViewType(int position) {
        return isHeader(position) ? 0 : 1;
    }

    private boolean isHeader(int position) {
        return mRows != null && mRows.isHeader(position);
    }

    /** Перевести курсор на элемент, соответствующий строке {@code position}. */
    private boolean move(int position) {
        if (mCursor == null) {
            return false;
        }
        int pos = mRows != null ? mRows.position(position) : position;
        return mCursor.moveToPosition(pos);
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (isHeader(position)) {
            TextView head = (TextView) convertView;
            if (head == null) {
                head = (TextView) mInf.inflate(R.layout.row_section, parent, false);
            }
            head.setText(mRows.text(position));
            return head;
        }
        View row = convertView;
        if (row == null) {
            row = mInf.inflate(R.layout.row_group, parent, false);
        }
        if (!move(position)) {
            return row;
        }
        ((ImageView) row.findViewById(R.id.group_icon)).setImageResource(mIcon);
        ((TextView) row.findViewById(R.id.group_name)).setText(mCursor.getString(mNameCol));
        ((TextView) row.findViewById(R.id.group_count)).setText(
                String.valueOf(mCursor.getLong(mCountCol)));
        return row;
    }
}
