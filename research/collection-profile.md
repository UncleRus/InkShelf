# Профиль реальной FB2-коллекции

Источников (корней): 2. Файлов `*.fb2.zip`: **677**.

Парсинг потоковый (`zipfile` + `ET.iterparse`, `elem.clear()` на каждом `end`, `<binary>` не удерживается в памяти).

## 1. Целостность zip / наличие .fb2

- Открываются как zip: **677 (100.0%)**
- Битых/нечитаемых zip: **0**
- Содержат entry с расширением `.fb2` (регистронезависимо): **677 (100.0%)**
- Без `.fb2`-entry (fallback или пропуск): **0**
- Успешно разобраны XML: **677 (100.0%)**
- Ошибка парсинга XML: **0**
- Несколько `.fb2`-entry в одном zip: 0
- Идентификаторов `.fb2` в имени entry в cp866: 54 (не-ASCII имён entry всего: 54)
- Имя entry **совпадает** с именем zip: 222; **отличается**: 455 (из них «информативных», с id/hex-id в имени: 396)
- Версия FB2: `(нет)` → 677
- Уникальных внутренних имён entry: 617; групп дубликатов (одно entry-имя в разных zip): 53, файлов в них: 113

**Примеры битых zip**

_нет_

**Примеры zip без .fb2**

_нет_

**Примеры ошибок парсинга XML**

_нет_

**Примеры имён entry в cp866**

- `Архангельский Михаил. (сатирический боевик) Русские идут!.fb2.zip — raw='Çασá¡úÑ½∞ß¬¿⌐ î¿σá¿½. (ßáΓ¿α¿τÑß¬¿⌐ í«Ñó¿¬) Éπßß¬¿Ñ ¿ñπΓ!.fb2' → cp866='Архангельский Михаил. (сатирический боевик) Русские идут!.fb2'`
- `Асс Павел. (Поросята - 1) Литераторы Дамкин и Стрекозов.fb2.zip — raw='Çßß ÅáóÑ½. (Å«α«ß∩Γá - 1) ï¿ΓÑαáΓ«αδ äá¼¬¿¡ ¿ æΓαÑ¬«º«ó.fb2' → cp866='Асс Павел. (Поросята - 1) Литераторы Дамкин и Стрекозов.fb2'`
- `Бабченко Аркадий. Война и мир (по принуждению).fb2.zip — raw='üáíτÑ¡¬« Çα¬áñ¿⌐. é«⌐¡á ¿ ¼¿α (»« »α¿¡πªñÑ¡¿ε).fb2' → cp866='Бабченко Аркадий. Война и мир (по принуждению).fb2'`
- `Белогорский Евгений. Во славу Отечества!.fb2.zip — raw='üÑ½«ú«αß¬¿⌐ àóúÑ¡¿⌐. é« ß½áóπ ÄΓÑτÑßΓóá!.fb2' → cp866='Белогорский Евгений. Во славу Отечества!.fb2'`
- `Беразинский Дмитрий. (Легенды Зачернодырья - 1) Путь, исполненный отваги.fb2.zip — raw='üÑαáº¿¡ß¬¿⌐ ä¼¿Γα¿⌐. (ïÑúÑ¡ñδ çáτÑα¡«ñδα∞∩ - 1) ÅπΓ∞, ¿ß»«½¡Ñ¡¡δ⌐ «Γóáú¿.fb2' → cp866='Беразинский Дмитрий. (Легенды Зачернодырья - 1) Путь, исполненный отваги.fb2'`
- `Беразинский Дмитрий. (Легенды Зачернодырья - 2) Задолго до Истмата.fb2.zip — raw='üÑαáº¿¡ß¬¿⌐ ä¼¿Γα¿⌐. (ïÑúÑ¡ñδ çáτÑα¡«ñδα∞∩ - 2) çáñ«½ú« ñ« êßΓ¼áΓá.fb2' → cp866='Беразинский Дмитрий. (Легенды Зачернодырья - 2) Задолго до Истмата.fb2'`
- `Брауэр Алан. Протяженный сексуальный оргазм.fb2.zip — raw='üαáπφα Ç½á¡. Åα«Γ∩ªÑ¡¡δ⌐ ßÑ¬ßπá½∞¡δ⌐ «αúáº¼.fb2' → cp866='Брауэр Алан. Протяженный сексуальный оргазм.fb2'`
- `Ванек Карел. (Бравый солдат Швейк - 2) Похождения бравого солдата Швейка во время мировой войны. Окончание.fb2.zip — raw='éá¡Ñ¬ èáαÑ½. (üαáóδ⌐ ß«½ñáΓ ÿóÑ⌐¬ - 2) Å«σ«ªñÑ¡¿∩ íαáó«ú« ß«½ñáΓá ÿóÑ⌐¬á ó« óαÑ¼∩ ¼¿α«ó«⌐ ó«⌐¡δ. Ä¬«¡τá¡¿Ñ.fb2' → cp866='Ванек Карел. (Бравый солдат Швейк - 2) Похождения бравого солдата Швейка во время мировой войны. Окончание.fb2'`
- `Ванек Карел. Приключения бравого солдата Швейка в русском плену.fb2.zip — raw='éá¡Ñ¬ èáαÑ½. Åα¿¬½ετÑ¡¿∩ íαáó«ú« ß«½ñáΓá ÿóÑ⌐¬á ó απßß¬«¼ »½Ñ¡π.fb2' → cp866='Ванек Карел. Приключения бравого солдата Швейка в русском плену.fb2'`
- `Витковский Алексей. (Витязь - 1) Витязь.fb2.zip — raw='é¿Γ¬«óß¬¿⌐ Ç½Ñ¬ßÑ⌐. (é¿Γ∩º∞ - 1) é¿Γ∩º∞.fb2' → cp866='Витковский Алексей. (Витязь - 1) Витязь.fb2'`
- `Витковский Алексей. (Витязь - 2) Выбор воина.fb2.zip — raw='é¿Γ¬«óß¬¿⌐ Ç½Ñ¬ßÑ⌐. (é¿Γ∩º∞ - 2) éδí«α ó«¿¡á.fb2' → cp866='Витковский Алексей. (Витязь - 2) Выбор воина.fb2'`
- `Гаркушев Евгений. Кодекс чести.fb2.zip — raw='âáα¬πΦÑó àóúÑ¡¿⌐. è«ñÑ¬ß τÑßΓ¿.fb2' → cp866='Гаркушев Евгений. Кодекс чести.fb2'`
- `Геннадьевич Ивакин. Мы погибнем вчера.fb2.zip — raw='âÑ¡¡áñ∞Ñó¿τ êóá¬¿¡. îδ »«ú¿í¡Ñ¼ óτÑαá.fb2' → cp866='Геннадьевич Ивакин. Мы погибнем вчера.fb2'`
- `Гордашевский Пётр. Их было четверо.fb2.zip — raw='â«αñáΦÑóß¬¿⌐ Å±Γα. êσ íδ½« τÑΓóÑα«.fb2' → cp866='Гордашевский Пётр. Их было четверо.fb2'`
- `Гордон Александр. (Беседы 2003 года - 03) Диалоги (март 2003 г.).fb2.zip — raw='â«αñ«¡ Ç½Ñ¬ßá¡ñα. (üÑßÑñδ 2003 ú«ñá - 03) ä¿á½«ú¿ (¼áαΓ 2003 ú.).fb2' → cp866='Гордон Александр. (Беседы 2003 года - 03) Диалоги (март 2003 г.).fb2'`
- `Гордон Александр. (Беседы 2003 года - 04) Диалоги (апрель 2003 г.).fb2.zip — raw='â«αñ«¡ Ç½Ñ¬ßá¡ñα. (üÑßÑñδ 2003 ú«ñá - 04) ä¿á½«ú¿ (á»αÑ½∞ 2003 ú.).fb2' → cp866='Гордон Александр. (Беседы 2003 года - 04) Диалоги (апрель 2003 г.).fb2'`
- `Гордон Александр. (Беседы 2003 года - 08) Диалоги (август 2003 г.).fb2.zip — raw='â«αñ«¡ Ç½Ñ¬ßá¡ñα. (üÑßÑñδ 2003 ú«ñá - 08) ä¿á½«ú¿ (áóúπßΓ 2003 ú.).fb2' → cp866='Гордон Александр. (Беседы 2003 года - 08) Диалоги (август 2003 г.).fb2'`
- `Гордон Александр. (Беседы 2003 года - 09) Диалоги (сентябрь 2003 г.).fb2.zip — raw='â«αñ«¡ Ç½Ñ¬ßá¡ñα. (üÑßÑñδ 2003 ú«ñá - 09) ä¿á½«ú¿ (ßÑ¡Γ∩íα∞ 2003 ú.).fb2' → cp866='Гордон Александр. (Беседы 2003 года - 09) Диалоги (сентябрь 2003 г.).fb2'`
- `Гордон Александр. (Беседы 2003 года - 10) Диалоги (октябрь 2003 г.).fb2.zip — raw='â«αñ«¡ Ç½Ñ¬ßá¡ñα. (üÑßÑñδ 2003 ú«ñá - 10) ä¿á½«ú¿ («¬Γ∩íα∞ 2003 ú.).fb2' → cp866='Гордон Александр. (Беседы 2003 года - 10) Диалоги (октябрь 2003 г.).fb2'`
- `Гордон Александр. (Беседы 2003 года - 11) Диалоги (ноябрь 2003 г.).fb2.zip — raw='â«αñ«¡ Ç½Ñ¬ßá¡ñα. (üÑßÑñδ 2003 ú«ñá - 11) ä¿á½«ú¿ (¡«∩íα∞ 2003 ú.).fb2' → cp866='Гордон Александр. (Беседы 2003 года - 11) Диалоги (ноябрь 2003 г.).fb2'`
- `Гордон Александр. (Беседы 2003 года - 12) Диалоги (декабрь 2003 г.).fb2.zip — raw='â«αñ«¡ Ç½Ñ¬ßá¡ñα. (üÑßÑñδ 2003 ú«ñá - 12) ä¿á½«ú¿ (ñÑ¬áíα∞ 2003 ú.).fb2' → cp866='Гордон Александр. (Беседы 2003 года - 12) Диалоги (декабрь 2003 г.).fb2'`
- `Гордон Александр. (Беседы 2003 года - 5) Диалоги (май 2003 г.).fb2.zip — raw='â«αñ«¡ Ç½Ñ¬ßá¡ñα. (üÑßÑñδ 2003 ú«ñá - 5) ä¿á½«ú¿ (¼á⌐ 2003 ú.).fb2' → cp866='Гордон Александр. (Беседы 2003 года - 5) Диалоги (май 2003 г.).fb2'`
- `Гордон Александр. (Беседы 2003 года - 6) Диалоги (июнь 2003 г.).fb2.zip — raw='â«αñ«¡ Ç½Ñ¬ßá¡ñα. (üÑßÑñδ 2003 ú«ñá - 6) ä¿á½«ú¿ (¿ε¡∞ 2003 ú.).fb2' → cp866='Гордон Александр. (Беседы 2003 года - 6) Диалоги (июнь 2003 г.).fb2'`
- `Гордон Александр. (Беседы 2003 года - 7) Диалоги (июль 2003 г.).fb2.zip — raw='â«αñ«¡ Ç½Ñ¬ßá¡ñα. (üÑßÑñδ 2003 ú«ñá - 7) ä¿á½«ú¿ (¿ε½∞ 2003 ú.).fb2' → cp866='Гордон Александр. (Беседы 2003 года - 7) Диалоги (июль 2003 г.).fb2'`
- `Горюнов Андрей. На пути Орды.fb2.zip — raw='â«αε¡«ó Ç¡ñαÑ⌐. ìá »πΓ¿ Äαñδ.fb2' → cp866='Горюнов Андрей. На пути Орды.fb2'`

**Примеры: внутреннее имя entry ≠ имя zip-файла**

- `zip='1. sergej_lukyanenko-sem_dnej_do_megiddo-61f1abced6d43.fb2.zip' → entry='Lukyanenko_Izmenyonnye-Lukyanenko-_1_Sem-dney-do-Megiddo.BqJCUw.635994.fb2'`
- `zip='1970.fb2.zip' → entry='Shchepetnov_1970.VmBSCg.551870.fb2'`
- `zip='1971. Восхождение.fb2.zip' → entry='Shchepetnov_Mihail-Karpov_3_1971-Voshozhdenie.3p68Ug.574832.fb2'`
- `zip='1_sergej_lukyanenko-sem_dnej_do_megiddo-61f1abced6d43.fb2.zip' → entry='Lukyanenko_Izmenyonnye-Lukyanenko-_1_Sem-dney-do-Megiddo.BqJCUw.635994.fb2'`
- `zip='2. sergej_lukyanenko-tri_dnya_indigo-61f1a57ab0720.fb2.zip' → entry='Lukyanenko_Izmenyonnye-Lukyanenko-_2_Tri-dnya-Indigo.0HwV-g.643950.fb2'`
- `zip='2_sergej_lukyanenko-tri_dnya_indigo-61f1a57ab0720.fb2.zip' → entry='Lukyanenko_Izmenyonnye-Lukyanenko-_2_Tri-dnya-Indigo.0HwV-g.643950.fb2'`
- `zip='3. _--623004696ad57.fb2.zip' → entry='Lukyanenko_Izmenyonnye_3_Mesyac-za-Rubikonom.rLkqLw.656415.fb2'`
- `zip='30 сребреников.fb2.zip' → entry='Raspopov_30-srebrenikov_1_30-srebrenikov.H-LnFw.807573.fb2'`
- `zip='3__--623004696ad57.fb2.zip' → entry='Lukyanenko_Izmenyonnye_3_Mesyac-za-Rubikonom.rLkqLw.656415.fb2'`
- `zip='4. _--634eb441e0b03.fb2.zip' → entry='Lukyanenko_Izmenyonnye_4_Leto-volontyora.6_lsZA.693139.fb2'`
- `zip='4__--634eb441e0b03.fb2.zip' → entry='Lukyanenko_Izmenyonnye_4_Leto-volontyora.6_lsZA.693139.fb2'`
- `zip='9370891.fb2.zip' → entry='51722014.fb2'`
- `zip='Chelyaber.fb2.zip' → entry='Panteley_Vtoroe-prishestvie_2_Chelyaber.Bg5G6w.680517.fb2'`
- `zip='Vita Nostra.fb2.zip' → entry='Dyachenko_Metamorfozy_1_Vita-Nostra.EbP6-Q.129885.fb2'`
- `zip='_--623004696ad57.fb2.zip' → entry='Lukyanenko_Izmenyonnye_3_Mesyac-za-Rubikonom.rLkqLw.656415.fb2'`
- `zip='_--6296427fb5a4d.fb2.zip' → entry='Kontorovich_Cenitel-chaynyh-ceremoniy.X03DkQ.667501.fb2'`
- `zip='_--634ea88956a0d.fb2.zip' → entry='Koshcheev_Romanov_5_Romanov-Tom-5.jTVdFg.692813.fb2'`
- `zip='_--634eb441e0b03.fb2.zip' → entry='Lukyanenko_Izmenyonnye_4_Leto-volontyora.6_lsZA.693139.fb2'`
- `zip='_--646cecbfcbc45.fb2.zip' → entry='Zampolit_Nevernyy-leninec_1_Provokator.tOB_ZQ.732674.fb2'`
- `zip='_--64deab866630a.fb2.zip' → entry='Kapba_Ne-chitayte-sovetskih-gazet_5_Britva-Okkama-v-SSSR.cOs85w.745156.fb2'`
- `zip='_--6540399b55dbb.fb2.zip' → entry='Borchaninov_Orki-Borchaninov-_2_Da-uruk-hai-my-.Ue_LHA.754366.fb2'`
- `zip='_--6665b7003a437.fb2.zip' → entry='Gorbov_Dyadya-samyh-chestnyh-pravil_7_Dyadya-samyh-chestnyh-pravil-7.-isNxA.788366.fb2'`
- `zip='_--6688e15f31ae3.fb2.zip' → entry='Koshcheev_Morov_3_Morov-Tom-3.LVjVWA.791526.fb2'`
- `zip='_--66e9e4fb26bce.fb2.zip' → entry='Semin_Perelomnyy-vek_1_Konstruktor.MlQ-Vg.799176.fb2'`
- `zip='_--671739943c9a8.fb2.zip' → entry='Semin_Perelomnyy-vek_2_Partiec-SI-.ClnMhw.802763.fb2'`

## 2. Кодировки (XML-декларация)

- `UTF-8` — 616 (91.0%)
- `utf-8` — 37 (5.5%)
- `windows-1251` — 20 (3.0%)
- `Windows-1251` — 3 (0.4%)
- `utf-16` — 1 (0.1%)

Без декларации: **0**. Неизвестный/неподдержанный кодек: 0.

## 3. Метаданные `<title-info>`

- Есть `<description>`: 677 (100.0%)
- Есть `<title-info>`: 677 (100.0%)
- Есть непустой `<book-title>`: 677 (100.0%)
- Есть хотя бы один `<author>`: **677 (100.0%)**
  - авторов: 0 → 0, 1 → 624, 2 → 53, 3+ → 0
- Есть `<sequence>`: **574 (84.8%)**
  - серий у книги: 0 → 103, 1 → 565, 2 → 9, 3+ → 0
  - `<sequence>` с атрибутом `number`: 511; без: 72; книг хотя бы с одним номером: 510
- Есть `<genre>`: **676 (99.9%)**
  - жанров у книги: 0 → 1, 1 → 200, 2 → 228, 3+ → 248
- Есть `<annotation>`: **657 (97.0%)**
- Есть `<coverpage>`: **639 (94.4%)**
  - с `<image xlink:href>`: 639 (94.4%)
  - из них с существующим `<binary>` по этому href: **639 (100.0%)** (от coverpage с href)

**coverpage со ссылкой, но без соответствующего <binary>**

_нет_

## 4. Жанры

Различных кодов `<genre>`: **74** (после нормализации lower/dash→underscore: 69). Вхождений всего: 1461; пустых: 0.

Топ-60 (код → число книг):

| код | книг |
| --- | ---: |
| `network_literature` | 304 |
| `popadancy` | 203 |
| `sf_history` | 176 |
| `sf_action` | 153 |
| `sf_fantasy` | 106 |
| `sf_social` | 50 |
| `sf_technofantasy` | 46 |
| `sf` | 44 |
| `sf_fantasy_city` | 38 |
| `sf_heroic` | 37 |
| `sf_space` | 33 |
| `sf_humor` | 30 |
| `sf_detective` | 28 |
| `boyar_anime` | 21 |
| `sf_litrpg` | 13 |
| `prose_contemporary` | 12 |
| `humor_prose` | 11 |
| `science` | 11 |
| `adv_history` | 9 |
| `adventure` | 7 |
| `fanfiction` | 7 |
| `sf_postapocalyptic` | 7 |
| `dorama` | 5 |
| `humor` | 5 |
| `nonf_biography` | 5 |
| `sf-history` | 5 |
| `sf_cyberpunk` | 5 |
| `urban_fantasy` | 5 |
| `nonf_publicism` | 4 |
| `novel` | 4 |
| `popadantsy-v-magicheskie-miry` | 4 |
| `prose_military` | 4 |
| `sci_phys` | 4 |
| `unfinished` | 4 |
| `back-to-ussr` | 3 |
| `fantasy_action` | 3 |
| `popadanec` | 3 |
| `popadantsy-vo-vremeni` | 3 |
| `sf_etc` | 3 |
| `sf_stimpank` | 3 |
| `adv_animal` | 2 |
| `adv_indian` | 2 |
| `det_action` | 2 |
| `fantasy-action` | 2 |
| `modern_tale` | 2 |
| `poetry` | 2 |
| `popadantsy` | 2 |
| `sci_history` | 2 |
| `action` | 1 |
| `antique_european` | 1 |
| `antique_myths` | 1 |
| `back_to_ussr` | 1 |
| `child_prose` | 1 |
| `child_sf` | 1 |
| `children` | 1 |
| `computers` | 1 |
| `fantasy` | 1 |
| `fantasy_fight` | 1 |
| `heroic-fantasy` | 1 |
| `home_sex` | 1 |

Полный список — в `genre-codes.txt`.

## 5. Особые случаи

- Без метаданных вообще (нет title-info/title/author/genre): **0** (показаны до 25)
- `<title-info>` есть, а `<book-title>` пуст: 0 (показаны до 25)
- XML-мусор/битая кодировка: ошибок парсинга 0
- UTF-16/32 без XML-декларации (только BOM): 0
- Книг без `<genre>`: 1
- Книг без `<coverpage>`: 25
- Есть `<body>`: 677; `<document-info>`: 676; `<publish-info>`: 628
- `<sequence number>` вида «1-2» и подобное: 0 (показаны до 50)

**Файлы без метаданных**

_нет_

**Пустой book-title**

_нет_

**Книги без genre**

- `/tmp/opencode/libprobe/books/internal/Books/Soth_Bez-obmana_5_Bez-obmana-5.821571.fb2.zip`

**Книги без coverpage**

- `/tmp/opencode/libprobe/books/internal/Books/Lukin_Lyck-Bakluzhino-Suslov_10_Po-tu-storonu.geMcmA.124575.fb2.zip`
- `/tmp/opencode/libprobe/books/internal/Books/Lukin_Lyck-Bakluzhino-Suslov_7_Zvonochek.753QkA.117307.fb2.zip`
- `/tmp/opencode/libprobe/books/internal/Books/Lukin_Lyck-Bakluzhino-Suslov_9_Lechitsya-budem.aSB9ag.97803.fb2.zip`
- `/tmp/opencode/libprobe/books/internal/Books/Lukin_Tupapau-ili-Skazka-o-zloy-zhene.lbPsDA.329301.fb2.zip`
- `/tmp/opencode/libprobe/books/internal/Books/Lukin_Ya-tvoy-plemyannik-Rodina-.pLxHmw.304154.fb2.zip`
- `/tmp/opencode/libprobe/books/internal/Books/Lukyanenko_Genom_3_Kaleki.XCxaQA.140141.fb2.zip`
- `/tmp/opencode/libprobe/books/sd/Books/4aganov4doc.fb2.zip`
- `/tmp/opencode/libprobe/books/sd/Books/4aganov5doc.fb2.zip`
- `/tmp/opencode/libprobe/books/sd/Books/Babkin_Priklyucheniya_cheloveka_v_shlyape.228189.fb2.zip`
- `/tmp/opencode/libprobe/books/sd/Books/Belogorskiy_Vostochnaya_voyna_SI_.231447.fb2.zip`
- `/tmp/opencode/libprobe/books/sd/Books/Dront_V-tu-zhe-reku_3_V-tu-zhe-reku-3.e6Mpww.612098.fb2.zip`
- `/tmp/opencode/libprobe/books/sd/Books/Haritonov_Fakap.7A0MNg.440185.fb2.zip`
- `/tmp/opencode/libprobe/books/sd/Books/Haritonov_Rubidiy.Qx8WaA.468907.fb2.zip`
- `/tmp/opencode/libprobe/books/sd/Books/Kulakov_Na_granitse_tuchi_hodyat_hmuro...221945.fb2.zip`
- `/tmp/opencode/libprobe/books/sd/Books/Pelevin_Kormlenie_krokodila_Hufu.122862.fb2.zip`
- `/tmp/opencode/libprobe/books/sd/Books/Pelevin_Kratkaya_istoriya_peyntbola_v_Moskve.42070.fb2.zip`
- `/tmp/opencode/libprobe/books/sd/Books/Vorobev_Ognennyy-sled_3_Ognennoe-nebo.951llQ.486526.fb2.zip`
- `/tmp/opencode/libprobe/books/sd/Books/Zagortsev_Tretya_mirovaya_80-yie.224888.fb2.zip`
- `/tmp/opencode/libprobe/books/sd/Books/mihail_yurevich_haritonov-fakap-621e5f355f941.fb2.zip`
- `/tmp/opencode/libprobe/books/sd/Books/sergej_simonov-dotyanutsya_do_zvyozd-61fbd90cb0a9d.fb2.zip`
- `/tmp/opencode/libprobe/books/sd/Books/Асс Павел. (Поросята - 1) Литераторы Дамкин и Стрекозов.fb2.zip`
- `/tmp/opencode/libprobe/books/sd/Books/В ту же реку 3.fb2.zip`
- `/tmp/opencode/libprobe/books/sd/Books/Данилов Дмитрий. (Гвардеец - 001) Гвардеец (Оболганная эпоха).fb2.zip`
- `/tmp/opencode/libprobe/books/sd/Books/Данилов Дмитрий. (Гвардеец - 2) Гвардеец – Дороги Европы.fb2.zip`
- `/tmp/opencode/libprobe/books/sd/Books/Демаскировка.fb2.zip`

**Многотомные sequence number**

_нет_

### Namespace FB2

- стандартный fictionbook 2.0 — 677

**Примеры нестандартных namespace**

_нет_

## 6. Имя файла и восстановимость автора/названия

- `flibusta_Author_Title[.Series]..rand.id` — 183 (27.0%)
- `free form (space)` — 159 (23.5%)
- `flibusta_author-title-hexid` — 147 (21.7%)
- `Author(s) Name. Title` — 93 (13.7%)
- `Title only / other` — 52 (7.7%)
- `Author_Title.id` — 34 (5.0%)
- `Author_Title` — 9 (1.3%)

- автор из имени файла: точно (разделитель '_', транслит) — 373 (55.1%)
- автор из имени файла: не выводится из имени (обычно только название) — 211 (31.2%)
- автор из имени файла: эвристически (через '. ' или пробел) — 93 (13.7%)

(В XML автор есть у 100% книг — имя файла нужно лишь как fallback.)

Примеры стемов (без `.fb2.zip`):

- `Lukin_Alaya-aura-protopartorga-Absolyutno-pravdivye-istorii-o-kudesnikah-magah-i-nechisti-samoy-raznoobraznoy.rMO0SA.735204` → шаблон `flibusta_Author_Title[.Series]..rand.id`, num_id='735204'
- `Lukyanenko_Nebesnoe-voinstvo_1_Sedmoy.830794` → шаблон `Author_Title.id`, num_id='830794'
- `1. sergej_lukyanenko-sem_dnej_do_megiddo-61f1abced6d43` → шаблон `flibusta_author-title-hexid`, num_id=None
- `1970` → шаблон `Title only / other`, num_id=None
- `1971. Восхождение` → шаблон `Author(s) Name. Title`, num_id=None
- `30 сребреников` → шаблон `free form (space)`, num_id=None

