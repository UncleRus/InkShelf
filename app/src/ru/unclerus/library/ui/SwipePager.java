package ru.unclerus.library.ui;

import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ListView;

/**
 * Листание страниц свайпом (PLAN §6.7, UI.md §5.2): справа налево — вперёд,
 * слева направо — назад (как в обычных читалках). Жест считается горизонтальным,
 * только если {@code |dx| > touchSlop} и {@code |dx| > |dy|} — вертикальная
 * прокрутка списка не перехватывается.
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
                        if (Math.abs(dx) < slop || Math.abs(dx) <= Math.abs(dy)) {
                            return false; // вертикальный жест — обычная прокрутка
                        }
                        if (dx > 0) {
                            target.onSwipeBack();
                        } else {
                            target.onSwipeForward();
                        }
                        return true;
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
