/*
 * Copyright (c) 2012, Codename One and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Codename One designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *  
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 * 
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 * 
 * Please contact Codename One through http://www.codenameone.com/ if you 
 * need additional information or have any questions.
 */
package com.codename1.impl.android;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import com.codename1.ui.Component;
import com.codename1.ui.Display;
import com.codename1.ui.Image;
import com.codename1.ui.Painter;
import com.codename1.ui.Stroke;
import com.codename1.ui.TextField;
import com.codename1.ui.geom.Rectangle;
import java.util.ArrayList;
import com.codename1.ui.Transform;
import com.codename1.ui.plaf.Style;
import com.codename1.ui.plaf.StyleAccessor;

public class AndroidAsyncView extends View implements CodenameOneSurface {

    abstract class AsyncOp {

        int clipX;
        int clipY;
        int clipW;
        int clipH;

        public AsyncOp(Rectangle clip) {
            if (clip == null) {
                clipW = cn1View.width;
                clipH = cn1View.height;
            } else {
                clipX = clip.getX();
                clipY = clip.getY();
                clipW = clip.getWidth();
                clipH = clip.getHeight();
            }
        }

        public void prepare() {}
        
        public void executeWithClip(AndroidGraphics underlying) {
            underlying.setClip(clipX, clipY, clipW, clipH);
            execute(underlying);
        }

        public abstract void execute(AndroidGraphics underlying);
    }
    private static final Object RENDERING_OPERATIONS_LOCK = new Object();
    private ArrayList<AsyncOp> renderingOperations = new ArrayList<AsyncOp>();
    private ArrayList<AsyncOp> pendingRenderingOperations = new ArrayList<AsyncOp>();
    private final CodenameOneView cn1View;
    private final AndroidGraphics graphics;
    private final AndroidGraphics internalGraphics;
    private final AndroidImplementation implementation;
    private boolean paintViewOnBuffer = false;
    static boolean legacyPaintLogic = true;

    public AndroidAsyncView(Activity activity, AndroidImplementation implementation) {
        super(activity);
        setId(2001);
        this.implementation = implementation;
        graphics = new AsyncGraphics(implementation);
        internalGraphics = new AndroidGraphics(implementation, null);
        cn1View = new CodenameOneView(activity, this, implementation, false);
        setWillNotCacheDrawing(true);
        setWillNotDraw(false);
        setBackgroundDrawable(null);
    }

    @Override
    protected void onDraw(Canvas c) {
        boolean paintOnBuffer = paintViewOnBuffer ||
                implementation.isEditingText() ||
                InPlaceEditView.isKeyboardShowing() ||
                implementation.nativePeers.size() > 0;

        internalGraphics.setCanvasNoSave(c);
        AndroidGraphics g = internalGraphics;
        if (paintOnBuffer) {
            g = cn1View.getGraphics();
        }
        for (AsyncOp o : renderingOperations) {
            o.executeWithClip(g);
        }
        synchronized (RENDERING_OPERATIONS_LOCK) {
            renderingOperations.clear();
            RENDERING_OPERATIONS_LOCK.notify();
        }

        if (paintOnBuffer) {
            cn1View.d(c);
        }
        if (implementation.isAsyncEditMode() && implementation.isEditingText()) {
            InPlaceEditView.reLayoutEdit();
        }
    }

    public void setPaintViewOnBuffer(boolean paintViewOnBuffer) {
        this.paintViewOnBuffer = paintViewOnBuffer;
    }

    @Override
    public boolean isOpaque() {
        return true;
    }

    private void visibilityChangedTo(boolean visible) {
        cn1View.visibilityChangedTo(visible);
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        this.visibilityChangedTo(visibility == View.VISIBLE);
        if(visibility != View.VISIBLE){
            paintViewOnBuffer = true;
        }
    }

    @Override
    protected void onSizeChanged(final int w, final int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        if (!Display.isInitialized()) {
            return;
        }
        Display.getInstance().callSerially(new Runnable() {
            public void run() {
                cn1View.handleSizeChange(w, h);
            }
        });
    }

    @Override
    public void flushGraphics(Rect rect) {
        //Log.d(Display.getInstance().getProperty("AppName", "CodenameOne"), "Flush graphics invoked with pending: " + pendingRenderingOperations.size() + " and current " + renderingOperations.size());

        // we might have pending entries in the rendering queue
        int counter = 0;
        while (!renderingOperations.isEmpty()) {
            try {
                synchronized (RENDERING_OPERATIONS_LOCK) {
                    RENDERING_OPERATIONS_LOCK.wait(5);
                }

                // don't let the EDT die here
                counter++;
                if (counter > 10) {
                    //Log.d(Display.getInstance().getProperty("AppName", "CodenameOne"), "Flush graphics timed out!!!");
                    return;
                }
            } catch (InterruptedException err) {
            }
        }
        ArrayList<AsyncOp> tmp = renderingOperations;
        renderingOperations = pendingRenderingOperations;
        pendingRenderingOperations = tmp;
        
        for (AsyncOp o : renderingOperations) {
            o.prepare();
        }        
        
        if (rect == null) {
            postInvalidate();
        } else {
            postInvalidate(rect.left, rect.top, rect.right, rect.bottom);
        }
        graphics.setClip(0, 0, cn1View.width, cn1View.height);
        graphics.setAlpha(255);
        graphics.setColor(0);
    }

    @Override
    public void flushGraphics() {
        flushGraphics(null);
    }

    @Override
    public boolean onKeyMultiple(int keyCode, int repeatCount, KeyEvent event) {
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (InPlaceEditView.isEditing()) {
            return true;
        }
        return cn1View.onKeyUpDown(true, keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (InPlaceEditView.isEditing()) {
            return true;
        }
        return cn1View.onKeyUpDown(false, keyCode, event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return cn1View.onTouchEvent(event);
    }

    @Override
    public AndroidGraphics getGraphics() {
        return graphics;
    }

    @Override
    public int getViewHeight() {
        return cn1View.height;
    }

    @Override
    public int getViewWidth() {
        return cn1View.width;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo editorInfo) {
        if (!Display.isInitialized() || Display.getInstance().getCurrent() == null) {
            return super.onCreateInputConnection(editorInfo);
        }
        cn1View.setInputType(editorInfo);
        return super.onCreateInputConnection(editorInfo);
    }

    @Override
    public boolean onCheckIsTextEditor() {
        if (!Display.isInitialized() || Display.getInstance().getCurrent() == null) {
            return false;
        }
        Component txtCmp = Display.getInstance().getCurrent().getFocused();
        if (txtCmp != null && txtCmp instanceof TextField) {
            return true;
        }
        return false;
    }

    @Override
    public View getAndroidView() {
        return this;
    }

    class AsyncGraphics extends AndroidGraphics {

        private Rectangle clip = null;
        private int alpha;
        private int color;
        private Paint imagePaint = new Paint();
        private Transform transform;


        AsyncGraphics(AndroidImplementation impl) {
            super(impl, null);
        }

        @Override
        public void rotate(final float angle, final int x, final int y) {
            pendingRenderingOperations.add(new AsyncOp(clip) {
                @Override
                public void execute(AndroidGraphics underlying) {
                    underlying.rotate(angle, x, y);
                }
            });
        }

        @Override
        public void rotate(final float angle) {
            pendingRenderingOperations.add(new AsyncOp(clip) {
                @Override
                public void execute(AndroidGraphics underlying) {
                    underlying.rotate(angle);
                }
            });
        }

        @Override
        public void scale(final float x, final float y) {
            pendingRenderingOperations.add(new AsyncOp(clip) {
                @Override
                public void execute(AndroidGraphics underlying) {
                    underlying.scale(x, y);
                }
            });
        }

        @Override
        public void resetAffine() {
            pendingRenderingOperations.add(new AsyncOp(clip) {
                @Override
                public void execute(AndroidGraphics underlying) {
                    underlying.resetAffine();
                }
            });
        }

        @Override
        public int getColor() {
            return color;
        }

        @Override
        public void clipRect(final int x, final int y, final int width, final int height) {
            if (clip == null) {
                clip = new Rectangle(x, y, width, height);
            } else {
                clip = clip.intersection(x, y, width, height);
            }
        }

        @Override
        public void setClip(final int x, final int y, final int width, final int height) {
            if (clip == null) {
                clip = new Rectangle(x, y, width, height);
            } else {
                clip.setX(x);
                clip.setY(y);
                clip.setWidth(width);
                clip.setHeight(height);
            }
        }

        @Override
        public int getClipY() {
            if (clip != null) {
                return clip.getY();
            }
            return 0;
        }

        @Override
        public int getClipX() {
            if (clip != null) {
                return clip.getX();
            }
            return 0;
        }

        @Override
        public int getClipWidth() {
            if (clip != null) {
                return clip.getWidth();
            }
            return cn1View.width;
        }

        @Override
        public int getClipHeight() {
            if (clip != null) {
                return clip.getHeight();
            }
            return cn1View.height;
        }

        @Override
        public void setAlpha(final int a) {
            this.alpha = a;
        }

        @Override
        public int getAlpha() {
            return alpha;
        }

        @Override
        public void fillRoundRect(final int x, final int y, final int width, final int height, final int arcWidth, final int arcHeight) {
            final int alph = alpha;
            final int col = color;
            pendingRenderingOperations.add(new AsyncOp(clip) {
                @Override
                public void execute(AndroidGraphics underlying) {
                    underlying.setAlpha(alph);
                    underlying.setColor(col);
                    underlying.fillRoundRect(x, y, width, height, arcWidth, arcHeight);
                }
            });
        }

        @Override
        public void fillRect(final int x, final int y, final int width, final int height) {
            if (alpha == 0) {
                return;
            }
            final int al = alpha;
            final int col = color;
            pendingRenderingOperations.add(new AsyncOp(clip) {
                @Override
                public void execute(AndroidGraphics underlying) {
                    underlying.setColor(col);
                    underlying.setAlpha(al);
                    underlying.fillRect(x, y, width, height);
                }
            });
        }

        @Override
        public void fillRect(final int x, final int y, final int w, final int h, byte alpha) {
            if (alpha == 0) {
                return;
            }
            final int preAlpha = this.alpha;
            final int al = alpha & 0xff;
            final int col = color;
            pendingRenderingOperations.add(new AsyncOp(clip) {
                @Override
                public void execute(AndroidGraphics underlying) {
                    underlying.setColor(col);
                    underlying.setAlpha(al);
                    underlying.fillRect(x, y, w, h);
                    underlying.setAlpha(preAlpha);
                }
            });
        }

        @Override
        public void fillLinearGradient(final int startColor, final int endColor, final int x, final int y, final int width, final int height, final boolean horizontal) {
            if (alpha == 0) {
                return;
            }
            final int al = alpha;
            pendingRenderingOperations.add(new AsyncOp(clip) {
                @Override
                public void execute(AndroidGraphics underlying) {
                    underlying.setAlpha(al);
                    underlying.fillLinearGradient(startColor, endColor, x, y, width, height, horizontal);
                }
            });
        }

        @Override
        public void fillRectRadialGradient(final int startColor, final int endColor, final int x, final int y,
                                           final int width, final int height, final float relativeX, final float relativeY, final float relativeSize) {
            if (alpha == 0) {
                return;
            }
            final int al = alpha;
            pendingRenderingOperations.add(new AsyncOp(clip) {
                @Override
                public void execute(AndroidGraphics underlying) {
                    underlying.setAlpha(al);
                    underlying.fillRectRadialGradient(startColor, endColor, x, y, width, height, relativeX, relativeY, relativeSize);
                }
            });
        }

        @Override
        public void fillRadialGradient(final int startColor, final int endColor, final int x, final int y, final int width, final int height) {
            if (alpha == 0) {
                return;
            }
            final int al = alpha;
            pendingRenderingOperations.add(new AsyncOp(clip) {
                @Override
                public void execute(AndroidGraphics underlying) {
                    underlying.setAlpha(al);
                    underlying.fillRadialGradient(startColor, endColor, x, y, width, height);
                }
            });
        }
        
        abstract class AsyncPaintPosition extends AsyncOp{
            int x;
            int y;
            int width;
            int height;
            int alpha;
            
            // the pending values allow changing this op on the Codename One EDT while the Android thread
            // renders it
            int pendingX;
            int pendingY;
            int pendingWidth;
            int pendingHeight;
            int pendingAlpha;

            int pendingClipX;
            int pendingClipY;
            int pendingClipW;
            int pendingClipH;

            public AsyncPaintPosition(Rectangle clip) {
                super(clip);
                pendingClipX = clipX;
                pendingClipY = clipY;
                pendingClipW = clipW;
                pendingClipH= clipH;
            }

            @Override
            public void prepare() {
                x = pendingX;
                y = pendingY;
                height = pendingHeight;
                width = pendingWidth;
                alpha = pendingAlpha;
                clipX = pendingClipX;
                clipY = pendingClipY;
                clipW = pendingClipW;
                clipH = pendingClipH;
            }

            @Override
            public void execute(AndroidGraphics underlying) {
                executeImpl(underlying);
            }
            
            public abstract void executeImpl(AndroidGraphics underlying);
        }
                
        @Override
        public void paintComponentBackground(final int x, final int y, final int width, final int height, final Style s) {
            if (alpha == 0 || width <= 0 || height <= 0) {
                return;
            }
            
            if(legacyPaintLogic) {
                final int al = alpha;
                pendingRenderingOperations.add(new AsyncOp(clip) {
                    @Override
                    public void execute(AndroidGraphics underlying) {
                        underlying.setAlpha(al);
                        underlying.paintComponentBackground(x, y, width, height, s);
                    }
                });
                return;
            }

            AsyncPaintPosition bgPaint = (AsyncPaintPosition)StyleAccessor.getCachedData(s);
            if(bgPaint == null) {
                final byte backgroundType = s.getBackgroundType() ;
                Image bgImageOrig = s.getBgImage();
                if (bgImageOrig == null) {
                    if(backgroundType >= Style.BACKGROUND_GRADIENT_LINEAR_VERTICAL) {
                        // TODO get gradients to work again...
                    } else {
                        // solid color paint
                        byte bgt = s.getBgTransparency();
                        if(bgt == 0) {
                            return;
                        }
                        bgPaint = paintBackgroundSolidColor(bgt, s, bgPaint);
                    }
                } else {
                    switch(backgroundType) {
                        case Style.BACKGROUND_NONE:
                            byte bgt = s.getBgTransparency();
                            if(bgt == 0) {
                                return;
                            }
                            bgPaint = paintBackgroundSolidColor(bgt, s, bgPaint);
                            break;
                        case Style.BACKGROUND_IMAGE_SCALED:
                            final Paint bgImageScalePaint = new Paint();
                            bgImageScalePaint.setXfermode(PORTER);
                            final Bitmap b = (Bitmap) bgImageOrig.getImage();
                            final Rect src = new Rect();
                            src.top = 0;
                            src.bottom = b.getHeight();
                            src.left = 0;
                            src.right = b.getWidth();
                            
                            bgPaint = new AsyncPaintPosition(clip) {                                
                                @Override
                                public void executeImpl(AndroidGraphics underlying) {
                                    Rect dest = new Rect();
                                    dest.top = y;
                                    dest.bottom = y + height;
                                    dest.left = x;
                                    dest.right = x + width;
                                    bgImageScalePaint.setAlpha(alpha);
                                    underlying.canvas.drawBitmap(b, src, dest, bgImageScalePaint);
                                }
                            };
                            break;
                        case Style.BACKGROUND_IMAGE_SCALED_FILL:
                            final Paint bgImageScaleFillPaint = new Paint();
                            bgImageScaleFillPaint.setXfermode(PORTER);
                            final Bitmap bFill = (Bitmap) bgImageOrig.getImage();
                            final Rect srcFill = new Rect();
                            srcFill.top = 0;
                            srcFill.bottom = bFill.getHeight();
                            srcFill.left = 0;
                            srcFill.right = bFill.getWidth();
                            final int iW = bgImageOrig.getWidth();
                            final int iH = bgImageOrig.getHeight();
                            
                            bgPaint = new AsyncPaintPosition(clip) {                                
                                @Override
                                public void executeImpl(AndroidGraphics underlying) {
                                    Rect dest = new Rect();
                                    float r = Math.max(((float)width) / ((float)iW), ((float)height) / ((float)iH));
                                    int bwidth = (int)(((float)iW) * r);
                                    int bheight = (int)(((float)iH) * r);
                                    x = x + (width - bwidth) / 2;
                                    y = y + (height - bheight) / 2; 
                                    dest.top = y;
                                    dest.bottom = y + bheight;
                                    dest.left = x;
                                    dest.right = x + bwidth;
                                    bgImageScaleFillPaint.setAlpha(alpha);
                                    underlying.canvas.drawBitmap(bFill, srcFill, dest, bgImageScaleFillPaint);
                                }
                            };
                            break;
                        case Style.BACKGROUND_IMAGE_SCALED_FIT:
                            final Paint bgImageScaleFitPaint = new Paint();
                            final Paint bgImageScaleFitColorPaint = new Paint();
                            final byte bgtScaleFitColorAlpha = s.getBgTransparency();
                            int cc = ((bgtScaleFitColorAlpha << 24) & 0xff000000) | (s.getBgColor() & 0xffffff);
                            bgImageScaleFitColorPaint.setAntiAlias(false);
                            bgImageScaleFitColorPaint.setStyle(Paint.Style.FILL);
                            bgImageScaleFitColorPaint.setColor(cc);
                            bgImageScaleFitPaint.setAlpha(255);

                            final Bitmap bFit = (Bitmap) bgImageOrig.getImage();
                            final Rect srcFit = new Rect();
                            srcFit.top = 0;
                            srcFit.bottom = bFit.getHeight();
                            srcFit.left = 0;
                            srcFit.right = bFit.getWidth();
                            final int iWFit = bgImageOrig.getWidth();
                            final int iHFit = bgImageOrig.getHeight();
                            
                            bgPaint = new AsyncPaintPosition(clip) {                                
                                @Override
                                public void executeImpl(AndroidGraphics underlying) {
                                    if(alpha > 0) {
                                        bgImageScaleFitColorPaint.setAlpha(alpha);
                                        underlying.canvas.drawRect(x, y, x + width, y + height, bgImageScaleFitColorPaint);
                                        return;
                                    }
                                    Rect dest = new Rect();
                                    float r2 = Math.min(((float)width) / ((float)iWFit), ((float)height) / ((float)iHFit));
                                    int awidth = (int)(((float)iWFit) * r2);
                                    int aheight = (int)(((float)iHFit) * r2);

                                    x = x + (width - awidth) / 2;
                                    y = y + (height - aheight) / 2; 
                                    dest.top = y;
                                    dest.bottom = y + aheight;
                                    dest.left = x;
                                    dest.right = x + awidth;
                                    underlying.canvas.drawBitmap(bFit, srcFit, dest, bgImageScaleFitPaint);
                                }
                            };
                            break;
                        case Style.BACKGROUND_IMAGE_TILE_BOTH:
                            final Paint bgImageTiledBothPaint = new Paint();
                            Bitmap bitmapTileBoth = (Bitmap) bgImageOrig.getImage();
                            BitmapShader shader = new BitmapShader(bitmapTileBoth, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
                            bgImageTiledBothPaint.setShader(shader);
                            bgImageTiledBothPaint.setAntiAlias(false);
                            
                            bgPaint = new AsyncPaintPosition(clip) {                                
                                @Override
                                public void executeImpl(AndroidGraphics underlying) {
                                    Rect dest = new Rect();
                                    dest.top = 0;
                                    dest.bottom = height;
                                    dest.left = 0;
                                    dest.right = width;
                                    bgImageTiledBothPaint.setAlpha(alpha);
                                    underlying.canvas.save();
                                    underlying.canvas.translate(x, y);
                                    underlying.canvas.concat(getTransformMatrix());
                                    underlying.canvas.drawRect(dest, bgImageTiledBothPaint);
                                    underlying.canvas.restore();
                                }
                            };
                            break;
                        case Style.BACKGROUND_IMAGE_TILE_HORIZONTAL_ALIGN_TOP:
                        case Style.BACKGROUND_IMAGE_TILE_HORIZONTAL_ALIGN_CENTER:
                        case Style.BACKGROUND_IMAGE_TILE_HORIZONTAL_ALIGN_BOTTOM:
                        case Style.BACKGROUND_IMAGE_TILE_VERTICAL_ALIGN_LEFT:
                        case Style.BACKGROUND_IMAGE_TILE_VERTICAL_ALIGN_CENTER:
                        case Style.BACKGROUND_IMAGE_TILE_VERTICAL_ALIGN_RIGHT:
                            final Paint bgImageTiledPaint = new Paint();
                            final Paint bgColorTiledPaint = new Paint();
                            final byte bgtTiledColorAlpha = s.getBgTransparency();
                            int c = ((bgtTiledColorAlpha << 24) & 0xff000000) | (s.getBgColor() & 0xffffff);
                            bgColorTiledPaint.setAntiAlias(false);
                            bgColorTiledPaint.setStyle(Paint.Style.FILL);
                            bgColorTiledPaint.setColor(c);
                            bgImageTiledPaint.setAlpha(255);
                            final Bitmap bitmapTile = (Bitmap) bgImageOrig.getImage();
                            BitmapShader shaderTile = new BitmapShader(bitmapTile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
                            bgImageTiledPaint.setShader(shaderTile);
                            bgImageTiledPaint.setAntiAlias(false);
                            
                            bgPaint = new AsyncPaintPosition(clip) {                                
                                @Override
                                public void executeImpl(AndroidGraphics underlying) {
                                    // fill the solid color
                                    underlying.canvas.drawRect(x, y, x + width, y + height, bgColorTiledPaint);

                                    switch(backgroundType) {
                                        case Style.BACKGROUND_IMAGE_TILE_HORIZONTAL_ALIGN_TOP:
                                            height = Math.min(bitmapTile.getHeight(), height);
                                            break;
                                                    
                                        case Style.BACKGROUND_IMAGE_TILE_HORIZONTAL_ALIGN_CENTER: {
                                            int iH = bitmapTile.getHeight();
                                            y = y + (height / 2 - iH / 2);
                                            height = iH;
                                            break;
                                        }
                                            
                                        case Style.BACKGROUND_IMAGE_TILE_HORIZONTAL_ALIGN_BOTTOM: {
                                            int iH = bitmapTile.getHeight();
                                            y = y + (height - iH);
                                            height = iH;
                                            break;
                                        }
                                        
                                        case Style.BACKGROUND_IMAGE_TILE_VERTICAL_ALIGN_LEFT: 
                                            width = bitmapTile.getWidth();
                                            break;
                                        
                                        case Style.BACKGROUND_IMAGE_TILE_VERTICAL_ALIGN_CENTER: {
                                            int iW = bitmapTile.getWidth();
                                            x = x + (width / 2 - iW / 2);
                                            width = iW;
                                            break;
                                        }
                                        
                                        case Style.BACKGROUND_IMAGE_TILE_VERTICAL_ALIGN_RIGHT: {
                                            int iW = bitmapTile.getWidth();
                                            x = x + width - iW;
                                            width = iW;
                                        }                                        
                                    }
                                    Rect dest = new Rect();
                                    dest.top = 0;
                                    dest.bottom = height;
                                    dest.left = 0;
                                    dest.right = width;
                                    underlying.canvas.save();
                                    underlying.canvas.translate(x, y);
                                    underlying.canvas.concat(getTransformMatrix());
                                    underlying.canvas.drawRect(dest, bgImageTiledPaint);
                                    underlying.canvas.restore();
                                }
                            };
                            break;
                    }
                }
                StyleAccessor.setCachedData(s, bgPaint);
            } else {
                if (clip == null) {
                    bgPaint.pendingClipW = cn1View.width;
                    bgPaint.pendingClipH = cn1View.height;
                    bgPaint.pendingClipX = 0;
                    bgPaint.pendingClipY = 0;
                } else {
                    bgPaint.pendingClipW = clip.getWidth();
                    bgPaint.pendingClipH = clip.getHeight();
                    bgPaint.pendingClipX = clip.getX();
                    bgPaint.pendingClipY = clip.getY();
                }
            }
            bgPaint.pendingX = x;
            bgPaint.pendingY = y;
            bgPaint.pendingHeight = height;
            bgPaint.pendingWidth = width;
            bgPaint.pendingAlpha = alpha;
            
            pendingRenderingOperations.add(bgPaint);
        }

        private AsyncPaintPosition paintBackgroundSolidColor(final byte bgt, Style s, AsyncPaintPosition bgPaint) {
            int c = ((bgt << 24) & 0xff000000) | (s.getBgColor() & 0xffffff);
            final Paint pnt = new Paint();
            pnt.setStyle(Paint.Style.FILL);
            pnt.setColor(c);
            pnt.setAntiAlias(false);
            bgPaint = new AsyncPaintPosition(clip) {
                @Override
                public void executeImpl(AndroidGraphics underlying) {
                    if(bgt == 0) {
                        return;
                    }
                    underlying.canvas.drawRect(x, y, x + width, y + height, pnt);
                }
            };
            return bgPaint;
        }
        
        @Override
        public void drawLabelComponent(final int cmpX, final int cmpY, final int cmpHeight, final int cmpWidth, final Style style, final String text,
                                       final Bitmap icon, final Bitmap stateIcon, final int preserveSpaceForState, final int gap, final boolean rtl, final boolean isOppositeSide,
                                       final int textPosition, final int stringWidth, final boolean isTickerRunning, final int tickerShiftText, final boolean endsWith3Points, final int valign) {
            if (clip == null) {
                clip = new Rectangle(cmpX, cmpY, cmpWidth, cmpHeight);
            } else {
                clip = clip.intersection(cmpX, cmpY, cmpWidth, cmpHeight);
            }
            final int al = alpha;
            pendingRenderingOperations.add(new AsyncOp(clip) {
                @Override
                public void execute(AndroidGraphics underlying) {
                    underlying.setAlpha(al);
                    underlying.drawLabelComponent(cmpX, cmpY, cmpHeight, cmpWidth, style, text, icon, stateIcon, preserveSpaceForState,
                            gap, rtl, isOppositeSide, textPosition, stringWidth, isTickerRunning, tickerShiftText, endsWith3Points, valign);
                }
            });
        }

        @Override
        public void fillArc(final int x, final int y, final int width, final int height, final int startAngle, final int arcAngle) {
            final int alph = alpha;
            final int col = color;
            pendingRenderingOperations.add(new AsyncOp(clip) {
                @Override
                public void execute(AndroidGraphics underlying) {
                    underlying.setAlpha(alph);
                    underlying.setColor(col);
                    underlying.fillArc(x, y, width, height, startAngle, arcAngle);
                }
            });
        }

        @Override
        public void drawArc(final int x, final int y, final int width, final int height, final int startAngle, final int arcAngle) {
            final int alph = alpha;
            final int col = color;
            pendingRenderingOperations.add(new AsyncOp(clip) {
                @Override
                public void execute(AndroidGraphics underlying) {
                    underlying.setAlpha(alph);
                    underlying.setColor(col);
                    underlying.drawArc(x, y, width, height, startAngle, arcAngle);
                }
            });
        }

        @Override
        public void drawString(final String str, final int x, final int y) {
            final int col = this.color;
            final CodenameOneTextPaint font = (CodenameOneTextPaint)getFont();
            final int alph = this.alpha;
            pendingRenderingOperations.add(new AsyncOp(clip) {
                @Override
                public void execute(AndroidGraphics underlying) {
                    underlying.setFont(font);
                    font.setColor(col);
                    font.setAlpha(alph);
                    underlying.drawString(str, x, y);
                }
            });
        }

        @Override
        public void drawRoundRect(final int x, final int y, final int width, final int height, final int arcWidth, final int arcHeight) {
            final int alph = alpha;
            final int col = color;
            pendingRenderingOperations.add(new AsyncOp(clip) {
                @Override
                public void execute(AndroidGraphics underlying) {
                    underlying.setAlpha(alph);
                    underlying.setColor(col);
                    underlying.drawRoundRect(x, y, width, height, arcWidth, arcHeight);
                }
            });
        }

        @Override
        public void drawRect(final int x, final int y, final int width, final int height) {
            final int alph = alpha;
            final int col = color;
            pendingRenderingOperations.add(new AsyncOp(clip) {
                @Override
                public void execute(AndroidGraphics underlying) {
                    underlying.setAlpha(alph);
                    underlying.setColor(col);
                    underlying.drawRect(x, y, width, height);
                }
            });
        }

        @Override
        public void drawRGB(final int[] rgbData, final int offset, final int x, final int y, final int w, final int h, final boolean processAlpha) {
            pendingRenderingOperations.add(new AsyncOp(clip) {
                @Override
                public void execute(AndroidGraphics underlying) {
                    Paint p = underlying.getPaint();
                    underlying.setPaint(imagePaint);
                    underlying.drawRGB(rgbData, offset, x, y, w, h, processAlpha);
                    underlying.setPaint(p);
                }
            });
        }

        @Override
        public void fillPolygon(final int[] xPoints, final int[] yPoints, final int nPoints) {
            final int alph = alpha;
            final int col = color;
            pendingRenderingOperations.add(new AsyncOp(clip) {
                @Override
                public void execute(AndroidGraphics underlying) {
                    underlying.setAlpha(alph);
                    underlying.setColor(col);
                    underlying.fillPolygon(xPoints, yPoints, nPoints);
                }
            });
        }

        @Override
        public void drawPolygon(final int[] xPoints, final int[] yPoints, final int nPoints) {
            final int alph = alpha;
            final int col = color;
            pendingRenderingOperations.add(new AsyncOp(clip) {
                @Override
                public void execute(AndroidGraphics underlying) {
                    underlying.setAlpha(alph);
                    underlying.setColor(col);
                    underlying.drawPolygon(xPoints, yPoints, nPoints);
                }
            });
        }

        @Override
        public void drawLine(final int x1, final int y1, final int x2, final int y2) {
            final int alph = alpha;
            final int col = color;
            pendingRenderingOperations.add(new AsyncOp(clip) {
                @Override
                public void execute(AndroidGraphics underlying) {
                    underlying.setAlpha(alph);
                    underlying.setColor(col);
                    underlying.drawLine(x1, y1, x2, y2);
                }
            });
        }

        @Override
        public void tileImage(final Object img, final int x, final int y, final int w, final int h) {
            final int alph = alpha;
            pendingRenderingOperations.add(new AsyncOp(clip) {
                @Override
                public void execute(AndroidGraphics underlying) {
                    Paint p = underlying.getPaint();
                    underlying.setPaint(imagePaint);
                    imagePaint.setAlpha(alph);
                    underlying.tileImage(img, x, y, w, h);
                    underlying.setPaint(p);
                }
            });
        }

        @Override
        public void drawImage(final Object img, final int x, final int y, final int w, final int h) {
            final int alph = alpha;
            pendingRenderingOperations.add(new AsyncOp(clip) {
                @Override
                public void execute(AndroidGraphics underlying) {
                    Paint p = underlying.getPaint();
                    underlying.setPaint(imagePaint);
                    imagePaint.setAlpha(alph);
                    underlying.drawImage(img, x, y, w, h);
                    underlying.setPaint(p);
                }
            });
        }

        @Override
        public void drawImage(final Object img, final int x, final int y) {
            final int alph = alpha;
            pendingRenderingOperations.add(new AsyncOp(clip) {
                @Override
                public void execute(AndroidGraphics underlying) {
                    Paint p = underlying.getPaint();
                    underlying.setPaint(imagePaint);
                    imagePaint.setAlpha(alph);
                    underlying.drawImage(img, x, y);
                    underlying.setPaint(p);
                }
            });
        }

        public void drawPath(final Path p, final Stroke stroke) {
            final int alph = alpha;
            final int col = color;

            pendingRenderingOperations.add(new AsyncOp(clip) {
                @Override
                public void execute(AndroidGraphics underlying) {
                    underlying.setAlpha(alph);
                    underlying.setColor(col);
                    underlying.drawPath(p, stroke);
                }
            });
        }

        public void fillPath(final Path p) {
            final int alph = alpha;
            final int col = color;

            pendingRenderingOperations.add(new AsyncOp(clip) {
                @Override
                public void execute(AndroidGraphics underlying) {
                    underlying.setAlpha(alph);
                    underlying.setColor(col);
                    //underlying.setTransform(transform);
                    underlying.fillPath(p);
                }
            });
        }

        public void setTransform(final Transform transform) {
            this.transform = transform;
            pendingRenderingOperations.add(new AsyncOp(clip) {
                @Override
                public void execute(AndroidGraphics underlying) {
                    underlying.setTransform(transform);
                }
            });
        }

        public Transform getTransform() {
            return transform;
        }

        @Override
        Paint getPaint() {
            return super.getPaint();
        }

        @Override
        void setColor(final int clr) {
            this.color = clr;
            /*pendingRenderingOperations.add(new AsyncOp(clip) {
             @Override
             public void execute(AndroidGraphics underlying) {
             underlying.setColor(clr);
             }
             });*/
        }

        private CodenameOneTextPaint font;

        @Override
        void setFont(final CodenameOneTextPaint font) {
            this.font = font;
        }

        @Override
        Paint getFont() {
            return font;
        }

        @Override
        void setCanvas(Canvas canvas) {
            //super.setCanvas(canvas); 
        }
    }

    public boolean alwaysRepaintAll() {
        return true;
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        //do not let other views steal the focus from the main view
        if(!gainFocus && implementation.hasViewAboveBelow()){
            requestFocus();
            if(implementation.getCurrentForm() != null){
                implementation.getCurrentForm().repaint();
            }
        }
    }

}
