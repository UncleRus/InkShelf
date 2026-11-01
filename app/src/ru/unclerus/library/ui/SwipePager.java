package ru.unclerus.library.ui;

import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ListView;

/**
 * Жесты свайпа по списку (PLAN §6.7, BACKLOG №2/№12). Оси разделены:
 * <ul>
 *   <li>вертикальный — листание страниц: вверх — вперёд, вниз — назад
 *       (страница подобрана по высоте экрана, см. {@link ListMetrics}/
 *       {@link PagePlan}, поэтому собственной прокрутки списку не нужно);</li>
 *   <li>горизонтальный — по умолчанию тоже листание (экраны без вкладок,
 *       например S2 «Книги группы»); экраны с нижним баром вкладок переопределяют
 *       {@link Target#onSwipeTabBack()}/{@link Target#onSwipeTabForward()}
 *       под переключение вкладок (S1).</li>
 * </ul>
 * При диагональном жесте доминирует горизонталь — так случайный диагональный
 * свайп идёт во вкладки, а не «уезжает» в листание по вертикали.
 */
public final class SwipePager {

    /** Приёмник свайпов. */
    public interface Target {
        void onSwipeForward();

        void onSwipeBack();

        /**
         * Горизонтальный свайп «влево» (палец справа налево). По умолчанию —
         * то же, что листание вперёд: на экранах без вкладок поведение не меняется.
         */
        default void onSwipeTabForward() {
            onSwipeForward();
        }

        /** Горизонтальный свайп «вправо» (палец слева направо). */
        default void onSwipeTabBack() {
            onSwipeBack();
        }
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
                                target.onSwipeTabBack();
                            } else {
                                target.onSwipeTabForward();
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
