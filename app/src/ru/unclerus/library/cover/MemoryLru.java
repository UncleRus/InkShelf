package ru.unclerus.library.cover;

import android.graphics.Bitmap;
import android.util.LruCache;

/**
 * LRU-кэш миниатюр «видимого окна» (PLAN §7, Фаза C). Ограничен не числом
 * записей, а <b>суммарным объёмом байт</b> {@link Bitmap#getByteCount()} —
 * обложки разного размера, поэтому считать по штукам нельзя (важно при 1.6 ГБ
 * ОЗУ). Вытеснение — платформенным {@link LruCache} (доступен с API 12).
 */
public final class MemoryLru extends LruCache<Long, Bitmap> {

    public MemoryLru(int maxBytes) {
        super(maxBytes);
    }

    @Override
    protected int sizeOf(Long key, Bitmap value) {
        return value == null ? 0 : value.getByteCount();
    }
}
