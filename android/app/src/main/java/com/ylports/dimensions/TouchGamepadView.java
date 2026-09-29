package com.ylports.dimensions;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

/**
 * Multi-touch Xbox-style overlay backed by one SDL3 virtual gamepad.
 *
 * Button IDs are private to this Android bridge; the native side maps them to
 * SDL_GAMEPAD_BUTTON_* so ReXGlue's existing SDL input driver sees a regular
 * controller rather than a special touch-only path.
 */
public final class TouchGamepadView extends View {
    private static final int BUTTON_A = 0;
    private static final int BUTTON_B = 1;
    private static final int BUTTON_X = 2;
    private static final int BUTTON_Y = 3;
    private static final int BUTTON_LB = 4;
    private static final int BUTTON_RB = 5;
    private static final int BUTTON_BACK = 6;
    private static final int BUTTON_START = 7;
    private static final int BUTTON_L3 = 8;
    private static final int BUTTON_R3 = 9;
    private static final int BUTTON_UP = 10;
    private static final int BUTTON_DOWN = 11;
    private static final int BUTTON_LEFT = 12;
    private static final int BUTTON_RIGHT = 13;

    private static final int TYPE_BUTTON = 0;
    private static final int TYPE_TRIGGER = 1;
    private static final int TYPE_STICK = 2;

    private static final class Binding {
        final int type;
        final int id;

        Binding(int type, int id) {
            this.type = type;
            this.id = id;
        }
    }

    private static final class ButtonZone {
        final int id;
        final String label;
        float x;
        float y;
        float radius;

        ButtonZone(int id, String label) {
            this.id = id;
            this.label = label;
        }

        boolean contains(float px, float py) {
            float dx = px - x;
            float dy = py - y;
            return dx * dx + dy * dy <= radius * radius;
        }
    }

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SparseArray<Binding> activeTouches = new SparseArray<>();

    private final ButtonZone[] buttons = {
        new ButtonZone(BUTTON_A, "A"),
        new ButtonZone(BUTTON_B, "B"),
        new ButtonZone(BUTTON_X, "X"),
        new ButtonZone(BUTTON_Y, "Y"),
        new ButtonZone(BUTTON_LB, "LB"),
        new ButtonZone(BUTTON_RB, "RB"),
        new ButtonZone(BUTTON_BACK, "◀"),
        new ButtonZone(BUTTON_START, "▶"),
        new ButtonZone(BUTTON_L3, "L3"),
        new ButtonZone(BUTTON_R3, "R3"),
        new ButtonZone(BUTTON_UP, "▲"),
        new ButtonZone(BUTTON_DOWN, "▼"),
        new ButtonZone(BUTTON_LEFT, "◀"),
        new ButtonZone(BUTTON_RIGHT, "▶"),
    };

    private final boolean[] buttonDown = new boolean[14];

    private float leftStickX;
    private float leftStickY;
    private float rightStickX;
    private float rightStickY;
    private float leftTrigger;
    private float rightTrigger;

    private float leftStickCx;
    private float leftStickCy;
    private float rightStickCx;
    private float rightStickCy;
    private float stickRadius;

    private final RectF leftTriggerRect = new RectF();
    private final RectF rightTriggerRect = new RectF();

    public TouchGamepadView(Context context) {
        super(context);
        setFocusable(false);
        setClickable(true);
        setBackgroundColor(Color.TRANSPARENT);

        fillPaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(dp(2));
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
    }

    public static TouchGamepadView install(Activity activity) {
        TouchGamepadView view = new TouchGamepadView(activity);
        activity.addContentView(
            view,
            new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        );
        return view;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        float unit = Math.min(width, height);

        stickRadius = unit * 0.115f;
        leftStickCx = width * 0.18f;
        leftStickCy = height * 0.73f;
        rightStickCx = width * 0.64f;
        rightStickCy = height * 0.73f;

        float faceCx = width * 0.875f;
        float faceCy = height * 0.68f;
        float faceOffset = unit * 0.078f;
        float faceRadius = unit * 0.047f;
        layoutButton(BUTTON_A, faceCx, faceCy + faceOffset, faceRadius);
        layoutButton(BUTTON_B, faceCx + faceOffset, faceCy, faceRadius);
        layoutButton(BUTTON_X, faceCx - faceOffset, faceCy, faceRadius);
        layoutButton(BUTTON_Y, faceCx, faceCy - faceOffset, faceRadius);

        float shoulderRadius = unit * 0.052f;
        layoutButton(BUTTON_LB, width * 0.18f, height * 0.105f, shoulderRadius);
        layoutButton(BUTTON_RB, width * 0.82f, height * 0.105f, shoulderRadius);

        float centerRadius = unit * 0.036f;
        layoutButton(BUTTON_BACK, width * 0.455f, height * 0.82f, centerRadius);
        layoutButton(BUTTON_START, width * 0.545f, height * 0.82f, centerRadius);

        float stickClickRadius = unit * 0.035f;
        layoutButton(BUTTON_L3, width * 0.37f, height * 0.88f, stickClickRadius);
        layoutButton(BUTTON_R3, width * 0.63f, height * 0.88f, stickClickRadius);

        float dpadCx = width * 0.105f;
        float dpadCy = height * 0.39f;
        float dpadOffset = unit * 0.056f;
        float dpadRadius = unit * 0.038f;
        layoutButton(BUTTON_UP, dpadCx, dpadCy - dpadOffset, dpadRadius);
        layoutButton(BUTTON_DOWN, dpadCx, dpadCy + dpadOffset, dpadRadius);
        layoutButton(BUTTON_LEFT, dpadCx - dpadOffset, dpadCy, dpadRadius);
        layoutButton(BUTTON_RIGHT, dpadCx + dpadOffset, dpadCy, dpadRadius);

        float triggerWidth = unit * 0.14f;
        float triggerHeight = unit * 0.075f;
        leftTriggerRect.set(
            width * 0.27f - triggerWidth / 2f,
            height * 0.04f,
            width * 0.27f + triggerWidth / 2f,
            height * 0.04f + triggerHeight
        );
        rightTriggerRect.set(
            width * 0.73f - triggerWidth / 2f,
            height * 0.04f,
            width * 0.73f + triggerWidth / 2f,
            height * 0.04f + triggerHeight
        );

        textPaint.setTextSize(unit * 0.035f);
    }

    private void layoutButton(int id, float x, float y, float radius) {
        for (ButtonZone zone : buttons) {
            if (zone.id == id) {
                zone.x = x;
                zone.y = y;
                zone.radius = radius;
                return;
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        drawStick(canvas, 0, leftStickCx, leftStickCy, leftStickX, leftStickY);
        drawStick(canvas, 1, rightStickCx, rightStickCy, rightStickX, rightStickY);

        for (ButtonZone zone : buttons) {
            drawButton(canvas, zone);
        }

        drawTrigger(canvas, leftTriggerRect, "LT", leftTrigger > 0f);
        drawTrigger(canvas, rightTriggerRect, "RT", rightTrigger > 0f);
    }

    private void drawStick(
        Canvas canvas,
        int stick,
        float cx,
        float cy,
        float axisX,
        float axisY
    ) {
        fillPaint.setColor(Color.argb(42, 255, 255, 255));
        strokePaint.setColor(Color.argb(105, 255, 255, 255));
        canvas.drawCircle(cx, cy, stickRadius, fillPaint);
        canvas.drawCircle(cx, cy, stickRadius, strokePaint);

        float knobRadius = stickRadius * 0.43f;
        float knobX = cx + axisX * (stickRadius - knobRadius);
        float knobY = cy + axisY * (stickRadius - knobRadius);
        fillPaint.setColor(Color.argb(92, 255, 255, 255));
        canvas.drawCircle(knobX, knobY, knobRadius, fillPaint);

        textPaint.setColor(Color.argb(150, 255, 255, 255));
        textPaint.setTextSize(Math.min(getWidth(), getHeight()) * 0.027f);
        canvas.drawText(stick == 0 ? "L" : "R", knobX, knobY + textPaint.getTextSize() * 0.34f, textPaint);
    }

    private void drawButton(Canvas canvas, ButtonZone zone) {
        boolean down = buttonDown[zone.id];
        fillPaint.setColor(
            down ? Color.argb(145, 255, 255, 255) : Color.argb(55, 255, 255, 255)
        );
        strokePaint.setColor(Color.argb(120, 255, 255, 255));
        canvas.drawCircle(zone.x, zone.y, zone.radius, fillPaint);
        canvas.drawCircle(zone.x, zone.y, zone.radius, strokePaint);

        textPaint.setColor(down ? Color.argb(230, 20, 20, 20) : Color.argb(190, 255, 255, 255));
        textPaint.setTextSize(Math.max(dp(11), zone.radius * 0.72f));
        canvas.drawText(
            zone.label,
            zone.x,
            zone.y - (textPaint.ascent() + textPaint.descent()) / 2f,
            textPaint
        );
    }

    private void drawTrigger(Canvas canvas, RectF rect, String label, boolean down) {
        fillPaint.setColor(
            down ? Color.argb(145, 255, 255, 255) : Color.argb(48, 255, 255, 255)
        );
        strokePaint.setColor(Color.argb(115, 255, 255, 255));
        float corner = rect.height() * 0.3f;
        canvas.drawRoundRect(rect, corner, corner, fillPaint);
        canvas.drawRoundRect(rect, corner, corner, strokePaint);

        textPaint.setColor(down ? Color.argb(230, 20, 20, 20) : Color.argb(190, 255, 255, 255));
        textPaint.setTextSize(rect.height() * 0.45f);
        canvas.drawText(
            label,
            rect.centerX(),
            rect.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f,
            textPaint
        );
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int actionIndex = event.getActionIndex();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                bindPointer(
                    event.getPointerId(actionIndex),
                    event.getX(actionIndex),
                    event.getY(actionIndex)
                );
                break;

            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < event.getPointerCount(); i++) {
                    int pointerId = event.getPointerId(i);
                    Binding binding = activeTouches.get(pointerId);
                    if (binding != null && binding.type == TYPE_STICK) {
                        updateStick(binding.id, event.getX(i), event.getY(i));
                    }
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                releasePointer(event.getPointerId(actionIndex));
                break;

            case MotionEvent.ACTION_CANCEL:
                releaseAll();
                break;

            default:
                break;
        }

        invalidate();
        return true;
    }

    private void bindPointer(int pointerId, float x, float y) {
        ButtonZone zone = hitButton(x, y);
        if (zone != null) {
            activeTouches.put(pointerId, new Binding(TYPE_BUTTON, zone.id));
            setButton(zone.id, true);
            return;
        }

        if (leftTriggerRect.contains(x, y)) {
            activeTouches.put(pointerId, new Binding(TYPE_TRIGGER, 0));
            setTrigger(0, 1f);
            return;
        }
        if (rightTriggerRect.contains(x, y)) {
            activeTouches.put(pointerId, new Binding(TYPE_TRIGGER, 1));
            setTrigger(1, 1f);
            return;
        }

        if (insideStick(x, y, leftStickCx, leftStickCy)) {
            activeTouches.put(pointerId, new Binding(TYPE_STICK, 0));
            updateStick(0, x, y);
            return;
        }
        if (insideStick(x, y, rightStickCx, rightStickCy)) {
            activeTouches.put(pointerId, new Binding(TYPE_STICK, 1));
            updateStick(1, x, y);
        }
    }

    private ButtonZone hitButton(float x, float y) {
        for (ButtonZone zone : buttons) {
            if (zone.contains(x, y)) return zone;
        }
        return null;
    }

    private boolean insideStick(float x, float y, float cx, float cy) {
        float dx = x - cx;
        float dy = y - cy;
        float reach = stickRadius * 1.18f;
        return dx * dx + dy * dy <= reach * reach;
    }

    private void updateStick(int stick, float x, float y) {
        float cx = stick == 0 ? leftStickCx : rightStickCx;
        float cy = stick == 0 ? leftStickCy : rightStickCy;
        float dx = (x - cx) / stickRadius;
        float dy = (y - cy) / stickRadius;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length > 1f) {
            dx /= length;
            dy /= length;
        }
        if (length < 0.07f) {
            dx = 0f;
            dy = 0f;
        }

        if (stick == 0) {
            leftStickX = dx;
            leftStickY = dy;
        } else {
            rightStickX = dx;
            rightStickY = dy;
        }
        nativeSetStick(stick, dx, dy);
    }

    private void releasePointer(int pointerId) {
        Binding binding = activeTouches.get(pointerId);
        if (binding == null) return;

        if (binding.type == TYPE_BUTTON) {
            setButton(binding.id, false);
        } else if (binding.type == TYPE_TRIGGER) {
            setTrigger(binding.id, 0f);
        } else if (binding.type == TYPE_STICK) {
            if (binding.id == 0) {
                leftStickX = 0f;
                leftStickY = 0f;
            } else {
                rightStickX = 0f;
                rightStickY = 0f;
            }
            nativeSetStick(binding.id, 0f, 0f);
        }

        activeTouches.remove(pointerId);
    }

    private void setButton(int id, boolean down) {
        if (id < 0 || id >= buttonDown.length || buttonDown[id] == down) return;
        buttonDown[id] = down;
        nativeSetButton(id, down);
    }

    private void setTrigger(int side, float value) {
        if (side == 0) {
            leftTrigger = value;
        } else {
            rightTrigger = value;
        }
        nativeSetTrigger(side, value);
    }

    public void releaseAll() {
        for (int id = 0; id < buttonDown.length; id++) {
            if (buttonDown[id]) {
                buttonDown[id] = false;
                nativeSetButton(id, false);
            }
        }
        leftTrigger = 0f;
        rightTrigger = 0f;
        nativeSetTrigger(0, 0f);
        nativeSetTrigger(1, 0f);

        leftStickX = leftStickY = rightStickX = rightStickY = 0f;
        nativeSetStick(0, 0f, 0f);
        nativeSetStick(1, 0f, 0f);
        activeTouches.clear();
        invalidate();
    }

    private static native void nativeSetStick(int stick, float x, float y);
    private static native void nativeSetTrigger(int side, float value);
    private static native void nativeSetButton(int button, boolean down);
}
