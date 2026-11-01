package ru.unclerus.library.fmt;

import java.io.File;

/**
 * Читатель метаданных одного формата (PLAN §4). Разбор потоковый: файл
 * читается один раз, крупные бинарные блоки (например {@code <binary>} в FB2)
 * не удерживаются в памяти — важно при ОЗУ 1.6 ГБ.
 */
public interface MetadataReader {

    /**
     * Разобрать метаданные файла.
     *
     * @throws Exception файл повреждён/недоступен — вызывающий ({@code ParsePhase})
     *                   поймает и поставит {@code meta_ok=0}, не прерывая индексацию
     */
    BookMeta read(File file) throws Exception;
}
