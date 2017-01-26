/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.andrewia.android.wearable.annotationface;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v7.graphics.Palette;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;

import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Watch face derived from sample analog face.
 * On devices with low-bit ambient mode, the hands are drawn without anti-aliasing in ambient mode.
 * The watch face is drawn with less contrast in mute mode.
 *
 * {@link AnalogWatchFaceService} is similar but has a sweep second hand.
 */
public class AnnotationFaceService extends CanvasWatchFaceService {
    private static final String TAG = "AnnotationFaceService";

    /*
     * Update rate in milliseconds for interactive mode. Update once a second to advance the
     * seconds, however they will appear in the final design.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private class Engine extends CanvasWatchFaceService.Engine {
        private static final int MSG_UPDATE_TIME = 0;

        private static final float HOUR_STROKE_WIDTH = 10f;
        private static final float MINUTE_STROKE_WIDTH = 6f;
        //private static final float SECOND_TICK_STROKE_WIDTH = 2f;
        private static final float BOX_STROKE_WIDTH = 2f; // TODO: Test 1 and 2 widths

        private static final float CENTER_GAP_AND_CIRCLE_RADIUS = 10f;

        private static final int SHADOW_RADIUS = 4;

        private Calendar mCalendar;
        private boolean mRegisteredTimeZoneReceiver = false;
        private boolean mMuteMode;

        private float mCenterX;
        private float mCenterY;

        //private float mSecondHandLength;
        private float sMinuteHandLength;
        private float sHourHandLength;

        /* Colors for all hands (hour, minute, seconds, ticks) based on photo loaded. */
        private int mWatchHandColor;
        //private int mWatchHandHighlightColor;
        private int mWatchHandShadowColor;

        private Paint mHourPaint;
        private Paint mMinutePaint;
        //private Paint mSecondPaint;
        private Paint mTickAndCirclePaint;
        private Paint mPrimaryBoxPaint;
        private Paint mSecondaryBoxPaint;
        private Paint mTextPaint;

        private Paint mBackgroundPaint;
        private Bitmap mBackgroundBitmap;
        private Bitmap mGrayBackgroundBitmap;

        private boolean mAmbient;
        private boolean mLowBitAmbient;
        private boolean mBurnInProtection;

        private Rect mPeekCardBounds = new Rect();
        private Rect mMainBoxBounds = new Rect();
        private Rect mSecondaryBoxBounds = new Rect();

        private float annotationHeight;
        private float annotationWidth;

        private final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };

        /* Handler to update the time once a second in interactive mode. */
        private final Handler mUpdateTimeHandler = new Handler() {
            @Override
            public void handleMessage(Message message) {

                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "updating time");
                }
                invalidate();
                if (shouldTimerBeRunning()) {
                    long timeMs = System.currentTimeMillis();
                    long delayMs = INTERACTIVE_UPDATE_RATE_MS
                            - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                    mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
                }

            }
        };

        @Override
        public void onCreate(SurfaceHolder holder) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onCreate");
            }
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(AnnotationFaceService.this)
                    .setAmbientPeekMode(WatchFaceStyle.AMBIENT_PEEK_MODE_HIDDEN)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_NONE)
                    //.setCardPeekMode(WatchFaceStyle.PEEK_MODE_SHORT)
                    .setHotwordIndicatorGravity(Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL)
                    .setStatusBarGravity(Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(Color.BLACK);
            mBackgroundBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.bg);

            /* Set defaults for colors */
            mWatchHandColor = Color.WHITE;
            //mWatchHandHighlightColor = Color.RED;
            mWatchHandShadowColor = Color.BLACK;

            mHourPaint = new Paint();
            mHourPaint.setColor(mWatchHandColor);
            mHourPaint.setStrokeWidth(HOUR_STROKE_WIDTH);
            mHourPaint.setAntiAlias(true);
            mHourPaint.setStrokeCap(Paint.Cap.ROUND);
            mHourPaint.setShadowLayer(SHADOW_RADIUS, 0, 0, mWatchHandShadowColor);

            mMinutePaint = new Paint();
            mMinutePaint.setColor(mWatchHandColor);
            mMinutePaint.setStrokeWidth(MINUTE_STROKE_WIDTH);
            mMinutePaint.setAntiAlias(true);
            mMinutePaint.setStrokeCap(Paint.Cap.ROUND);
            mMinutePaint.setShadowLayer(SHADOW_RADIUS, 0, 0, mWatchHandShadowColor);

            mTickAndCirclePaint = new Paint();
            mTickAndCirclePaint.setColor(mWatchHandColor);
            mTickAndCirclePaint.setStrokeWidth(MINUTE_STROKE_WIDTH);
            mTickAndCirclePaint.setAntiAlias(true);
            mTickAndCirclePaint.setStyle(Paint.Style.STROKE);
            mTickAndCirclePaint.setShadowLayer(SHADOW_RADIUS, 0, 0, mWatchHandShadowColor);

            mPrimaryBoxPaint = new Paint();
            mPrimaryBoxPaint.setColor(mWatchHandColor);
            mPrimaryBoxPaint.setStrokeWidth(BOX_STROKE_WIDTH);
            mPrimaryBoxPaint.setAntiAlias(true);
            mPrimaryBoxPaint.setStyle(Paint.Style.STROKE);
            mPrimaryBoxPaint.setShadowLayer(SHADOW_RADIUS, 0, 0, mWatchHandShadowColor);

            mSecondaryBoxPaint = new Paint();
            mSecondaryBoxPaint.setColor(mWatchHandColor);
            mSecondaryBoxPaint.setStrokeWidth(BOX_STROKE_WIDTH);
            mSecondaryBoxPaint.setAntiAlias(true);
            mSecondaryBoxPaint.setStyle(Paint.Style.STROKE);
            mSecondaryBoxPaint.setShadowLayer(SHADOW_RADIUS, 0, 0, mWatchHandShadowColor);

            mTextPaint = new Paint();
            mTextPaint.setColor(mWatchHandColor);
            mTextPaint.setStrokeWidth(BOX_STROKE_WIDTH);
            mTextPaint.setAntiAlias(true);
            mTextPaint.setStyle(Paint.Style.FILL);
            mTextPaint.setShadowLayer(SHADOW_RADIUS, 0, 0, mWatchHandShadowColor);
            mTextPaint.setTextSize(48);

            /* Extract colors from background image to improve watchface style. */
            Palette.generateAsync(
                    mBackgroundBitmap,
                    new Palette.PaletteAsyncListener() {
                        @Override
                        public void onGenerated(Palette palette) {
                            if (palette != null) {
                                if (Log.isLoggable(TAG, Log.DEBUG)) {
                                    Log.d(TAG, "Palette: " + palette);
                                }

                                //mWatchHandHighlightColor = palette.getVibrantColor(Color.RED);
                                mWatchHandColor = palette.getLightVibrantColor(Color.WHITE);
                                mWatchHandShadowColor = palette.getDarkMutedColor(Color.BLACK);
                                updateWatchHandStyle();
                            }
                        }
                    });

            mCalendar = Calendar.getInstance();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onPropertiesChanged: low-bit ambient = " + mLowBitAmbient);
            }

            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
            mBurnInProtection = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onAmbientModeChanged: " + inAmbientMode);
            }
            mAmbient = inAmbientMode;

            updateWatchHandStyle();

            /* Check and trigger whether or not timer should be running (only in active mode). */
            updateTimer();
        }

        private void updateWatchHandStyle(){
            if (mAmbient){
                mHourPaint.setColor(Color.WHITE);
                mMinutePaint.setColor(Color.WHITE);
                //mSecondPaint.setColor(Color.WHITE);
                mTickAndCirclePaint.setColor(Color.WHITE);

                mHourPaint.setAntiAlias(false);
                mMinutePaint.setAntiAlias(false);
                //mSecondPaint.setAntiAlias(false);
                mTickAndCirclePaint.setAntiAlias(false);

                mHourPaint.clearShadowLayer();
                mMinutePaint.clearShadowLayer();
                //mSecondPaint.clearShadowLayer();
                mTickAndCirclePaint.clearShadowLayer();

            } else {
                mHourPaint.setColor(mWatchHandColor);
                mMinutePaint.setColor(mWatchHandColor);
                // Set the box colors to be the same as watch hand colors
                mPrimaryBoxPaint.setColor(mWatchHandColor);
                mSecondaryBoxPaint.setColor(mWatchHandColor);
                //mSecondPaint.setColor(mWatchHandHighlightColor);
                mTickAndCirclePaint.setColor(mWatchHandColor);

                mHourPaint.setAntiAlias(true);
                mMinutePaint.setAntiAlias(true);
                //mSecondPaint.setAntiAlias(true);
                mTickAndCirclePaint.setAntiAlias(true);

                mHourPaint.setShadowLayer(SHADOW_RADIUS, 0, 0, mWatchHandShadowColor);
                mMinutePaint.setShadowLayer(SHADOW_RADIUS, 0, 0, mWatchHandShadowColor);
                //mSecondPaint.setShadowLayer(SHADOW_RADIUS, 0, 0, mWatchHandShadowColor);
                mTickAndCirclePaint.setShadowLayer(SHADOW_RADIUS, 0, 0, mWatchHandShadowColor);
                mPrimaryBoxPaint.setShadowLayer(SHADOW_RADIUS, 0, 0, mWatchHandShadowColor);
                mSecondaryBoxPaint.setShadowLayer(SHADOW_RADIUS, 0, 0, mWatchHandShadowColor);
            }
        }

        @Override
        public void onInterruptionFilterChanged(int interruptionFilter) {
            super.onInterruptionFilterChanged(interruptionFilter);
            boolean inMuteMode = (interruptionFilter == WatchFaceService.INTERRUPTION_FILTER_NONE);

            /* Dim display in mute mode. */
            if (mMuteMode != inMuteMode) {
                mMuteMode = inMuteMode;
                mHourPaint.setAlpha(inMuteMode ? 100 : 255);
                mMinutePaint.setAlpha(inMuteMode ? 100 : 255);
                //mSecondPaint.setAlpha(inMuteMode ? 80 : 255);
                invalidate();
            }
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);

            /*
             * Find the coordinates of the center point on the screen, and ignore the window
             * insets, so that, on round watches with a "chin", the watch face is centered on the
             * entire screen, not just the usable portion.
             */
            mCenterX = width / 2f;
            mCenterY = height / 2f;

            /*
             * Calculate lengths of different hands based on watch screen size.
             */
            //mSecondHandLength = (float) (mCenterX * 0.875);
            sMinuteHandLength = (float) (mCenterX * 0.75);
            sHourHandLength = (float) (mCenterX * 0.5);

            /*
             * Calculate size of annotation rectangles.
             * Annotations are 1/6 screen height and 1/3 screen width
             */
            annotationHeight = height / 6f;
            annotationWidth = width / 3f;


            /* Scale loaded background image (more efficient) if surface dimensions change. */
            float scale = ((float) width) / (float) mBackgroundBitmap.getWidth();

            mBackgroundBitmap = Bitmap.createScaledBitmap(mBackgroundBitmap,
                    (int) (mBackgroundBitmap.getWidth() * scale),
                    (int) (mBackgroundBitmap.getHeight() * scale), true);

            /*
             * Create a gray version of the image only if it will look nice on the device in
             * ambient mode. That means we don't want devices that support burn-in
             * protection (slight movements in pixels, not great for images going all the way to
             * edges) and low ambient mode (degrades image quality).
             *
             * Also, if your watch face will know about all images ahead of time (users aren't
             * selecting their own photos for the watch face), it will be more
             * efficient to create a black/white version (png, etc.) and load that when you need it.
             */
            if (!mBurnInProtection && !mLowBitAmbient) {
                initGrayBackgroundBitmap();
            }
        }

        private void initGrayBackgroundBitmap() {
            mGrayBackgroundBitmap = Bitmap.createBitmap(
                    mBackgroundBitmap.getWidth(),
                    mBackgroundBitmap.getHeight(),
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(mGrayBackgroundBitmap);
            Paint grayPaint = new Paint();
            ColorMatrix colorMatrix = new ColorMatrix();
            colorMatrix.setSaturation(0);
            ColorMatrixColorFilter filter = new ColorMatrixColorFilter(colorMatrix);
            grayPaint.setColorFilter(filter);
            canvas.drawBitmap(mBackgroundBitmap, 0, 0, grayPaint);
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "onDraw");
            }
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);

            if (mAmbient && (mLowBitAmbient || mBurnInProtection)) {
                canvas.drawColor(Color.BLACK);
            } else if (mAmbient) {
                canvas.drawBitmap(mGrayBackgroundBitmap, 0, 0, mBackgroundPaint);
            } else {
                canvas.drawBitmap(mBackgroundBitmap, 0, 0, mBackgroundPaint);
            }

            /*
             * Draw ticks. Usually you will want to bake this directly into the photo, but in
             * cases where you want to allow users to select their own photos, this dynamically
             * creates them on top of the photo.
             */
            float innerTickRadius = mCenterX - 10;
            float outerTickRadius = mCenterX;
            for (int tickIndex = 0; tickIndex < 12; tickIndex++) {
                float tickRot = (float) (tickIndex * Math.PI * 2 / 12);
                float innerX = (float) Math.sin(tickRot) * innerTickRadius;
                float innerY = (float) -Math.cos(tickRot) * innerTickRadius;
                float outerX = (float) Math.sin(tickRot) * outerTickRadius;
                float outerY = (float) -Math.cos(tickRot) * outerTickRadius;
                canvas.drawLine(mCenterX + innerX, mCenterY + innerY,
                        mCenterX + outerX, mCenterY + outerY, mTickAndCirclePaint);
            }

            /*
             * These calculations reflect the rotation in degrees per unit of time, e.g.,
             * 360 / 60 = 6 and 360 / 12 = 30.
             */
            //final float seconds =(mCalendar.get(Calendar.SECOND) + mCalendar.get(Calendar.MILLISECOND) / 1000f);
            //final float secondsRotation = seconds * 6f;

            final float minutesRotation = mCalendar.get(Calendar.MINUTE) * 6f;

            final float hourHandOffset = mCalendar.get(Calendar.MINUTE) / 2f;
            final float hoursRotation = (mCalendar.get(Calendar.HOUR) * 30) + hourHandOffset;
            Log.i("ANNOCALC", "Hour hand rotation is " + hoursRotation);

            /*
             * Find appropriate locations for the annotation boxes.
             * 0 corresponds to left side, 1 corresponds to bottom side, etc.
             */

            /*Log.i("DRAWCALC", "minutesRotation is " + minutesRotation);
            Log.i("DRAWCALC", "hoursRotation is " + hoursRotation + " including offset " + hourHandOffset);
            final boolean[] available = new boolean[4];
            // Go through positions and mark available if unoccupied
            if(minutesRotation < 45 && hoursRotation < 45 && minutesRotation > 135 && hoursRotation > 135) {
                available[0] = true;
            } if(minutesRotation < 135 && hoursRotation < 135 && minutesRotation > 225 && hoursRotation > 225) {
                available[1] = true;
            } if (minutesRotation < 225 && hoursRotation < 225 && minutesRotation > 315 && hoursRotation > 315) {
                available[2] = true;
            } if(minutesRotation < 315 && hoursRotation < 315 && minutesRotation > 45 && hoursRotation > 45) {
                available[3] = true;
            }
            int i;
            int mainAnnotationQuadrant = -1;
            int secondaryAnnotationQuadrant = -1;
            for(i = 0; i < 4; i++) {
                if(available[i]){
                    mainAnnotationQuadrant = i;
                }
            } for(i++; i < 4; i++) {
                if(available[i]){
                    secondaryAnnotationQuadrant = i;
                }
            }
            */

            /*
            * New idea: have annotation at top of hour hand
            */

            boolean drawAnnotationBelowHourHand = false;
            if(hoursRotation > 90 && hoursRotation < 270){
                drawAnnotationBelowHourHand = true;
            }

            /*
             * Draw the annotation boxes in the appropriate locations.
             */
            /*switch(mainAnnotationQuadrant) {
                case -1:
                    // Do nothing since there is no room to drawn a box
                case 0:
                    mMainBoxBounds.set((int)(mCenterX + (mCenterX / 12)), (int)(mCenterY - (mCenterY / 12)),
                            (int)(mCenterX * 2), (int)(mCenterY + (mCenterY / 12)) );
                case 1:
                    mMainBoxBounds.set((int)(mCenterX - (mCenterX / 6)), (int)(mCenterY + (mCenterY / 6)),
                            (int)(mCenterX + (mCenterX / 6)), (int)(mCenterY * 2) );
                case 2:
                    mMainBoxBounds.set((int)(0), (int)(mCenterY - (mCenterY / 12)),
                            (int)(mCenterX - (mCenterX / 12)), (int)(mCenterY + (mCenterY / 12)) );
                case 3:
                    mMainBoxBounds.set((int)(mCenterX - (mCenterX / 6)), (int)(0),
                            (int)(mCenterX + (mCenterX / 6)), (int)(mCenterY - (mCenterY / 6)) );
            }
            */

            //TODO: Fix bound calculation

            int xAnnotationOffset = (int)(Math.sin((hoursRotation) * (mCenterY / 12)));
            Log.i("ANNOCALC", "Annotation x offset is " + xAnnotationOffset);
            int bound = (int)(mCenterY - (Math.cos(Math.toRadians(hoursRotation)) * sHourHandLength));
            //Log.i("ANNOCALC", "General bound is " + bound + " from center " + mCenterY + " and length " + sHourHandLength + " calculated into y offset " + (Math.cos(Math.toRadians(hoursRotation)) * sHourHandLength));
            //Log.i("ANNOCALC", "Cosine operation results in " + Math.cos(hoursRotation));
            if(drawAnnotationBelowHourHand){
                mMainBoxBounds.set((int)(mCenterY / 8) + xAnnotationOffset, bound, (int)(2 * mCenterX) - (int)(mCenterX / 8) + xAnnotationOffset, bound + (int)(mCenterY / 3));
            } else{
                mMainBoxBounds.set((int)(mCenterY / 8) + xAnnotationOffset, bound - (int)(mCenterY / 3), (int)(2 * mCenterX) - (int)(mCenterY / 8) + xAnnotationOffset, bound);
            }
            canvas.drawRect(mMainBoxBounds, mPrimaryBoxPaint);
            //canvas.drawLine(0, 0, 20, 20, mPrimaryBoxPaint);

            canvas.drawText(
                    "NOT SET UP",
                    mMainBoxBounds.left + 8,
                    mMainBoxBounds.bottom - 8,
                    mTextPaint);


            // TODO: Work out secondary annotation box

            canvas.save(); // Save the canvas state before we can begin to rotate it.

            canvas.rotate(hoursRotation, mCenterX, mCenterY);
            canvas.drawLine(
                    mCenterX,
                    mCenterY - CENTER_GAP_AND_CIRCLE_RADIUS,
                    mCenterX,
                    mCenterY - sHourHandLength,
                    mHourPaint);

            canvas.rotate(minutesRotation - hoursRotation, mCenterX, mCenterY);
            canvas.drawLine(
                    mCenterX,
                    mCenterY - CENTER_GAP_AND_CIRCLE_RADIUS,
                    mCenterX,
                    mCenterY - sMinuteHandLength,
                    mMinutePaint);

            /*
             * Ensure the "seconds" hand is drawn only when we are in interactive mode.
             * Otherwise, we only update the watch face once a minute.
             */
            /* if (!mAmbient) {
                canvas.rotate(secondsRotation - minutesRotation, mCenterX, mCenterY);
                canvas.drawLine(
                        mCenterX,
                        mCenterY - CENTER_GAP_AND_CIRCLE_RADIUS,
                        mCenterX,
                        mCenterY - mSecondHandLength,
                        mSecondPaint);

            } */
            canvas.drawCircle(
                    mCenterX,
                    mCenterY,
                    CENTER_GAP_AND_CIRCLE_RADIUS,
                    mTickAndCirclePaint);

            /* Restore the canvas' original orientation. */
            canvas.restore();

            /* Draw rectangle behind peek card in ambient mode to improve readability. */
            if (mAmbient) {
                canvas.drawRect(mPeekCardBounds, mBackgroundPaint);
            }
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();
                /* Update time zone in case it changed while we weren't visible. */
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                unregisterReceiver();
            }

            /* Check and trigger whether or not timer should be running (only in active mode). */
            updateTimer();
        }

        @Override
        public void onPeekCardPositionUpdate(Rect rect) {
            super.onPeekCardPositionUpdate(rect);
            mPeekCardBounds.set(rect);
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            AnnotationFaceService.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            AnnotationFaceService.this.unregisterReceiver(mTimeZoneReceiver);
        }

        /**
         * Starts/stops the {@link #mUpdateTimeHandler} timer based on the state of the watch face.
         */
        private void updateTimer() {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "updateTimer");
            }
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer
         * should only run in active mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !mAmbient;
        }
    }
}
