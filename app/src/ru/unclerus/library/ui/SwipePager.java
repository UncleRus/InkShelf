package ru.unclerus.library.ui;

import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ListView;

/**
 * Листание страниц свайпом (PLAN §6.7, UI.md §5.2). Горизонтальный свайп:
 * справа налево — вперёд, слева направо — назад (как в обычных читалках).
 * Вертикальный свайп (номер 2 в {@code BACKLOG.md}) листает так же: вверх —
 * вперёд, вниз — назад. Страница списка подобрана по высоте экрана (см.
 * {@link ListMetrics}/{@link PagePlan}), поэтому собственная прокрутка списка
 * не нужна и вертикальный жест безопасно отдать листанию.
 */
public final class SwipePager {

    /** Приёмник свайпов. */
    public interface Target {
        void onSwipeForward();

        void onSwipeBack();
    }

    private SwipePager() {}

    public static void attach(ListView list, final Target target) {
        final GestureDetector det = new GestureDetector(list.getContext(),
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(MotionEvent e) {
                        return true;
                    }

                    @Override
                    public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                        if (e1 == null || e2 == null) {
                            return false;
                        }
                        float dx = e2.getX() - e1.getX();
                        float dy = e2.getY() - e1.getY();
                        float slop = 48f;
                        // Доминирующая ось решает: горизонтальный жест важнее при
                        // равенстве модулей — так случайный диагональный свайп не
                        // «уезжает» в листание по вертикали.
                        if (Math.abs(dx) >= slop && Math.abs(dx) > Math.abs(dy)) {
                            if (dx > 0) {
                                target.onSwipeBack();
                            } else {
                                target.onSwipeForward();
                            }
                            return true;
                        }
                        if (Math.abs(dy) >= slop && Math.abs(dy) > Math.abs(dx)) {
                            if (dy < 0) {
                                target.onSwipeForward(); // вверх — вперёд
                            } else {
                                target.onSwipeBack();    // вниз — назад
                            }
                            return true;
                        }
                        return false;
                    }
                });
        list.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                det.onTouchEvent(e);
                return false; // события идут и в ListView — прокрутка сохраняется
            }
        });
    }
}
