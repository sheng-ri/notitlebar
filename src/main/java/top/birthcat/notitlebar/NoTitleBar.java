package top.birthcat.notitlebar;

import sun.awt.windows.WToolkit;
import sun.awt.windows.WWindowPeer;

import java.awt.*;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.WrongMethodTypeException;

import static top.birthcat.notitlebar.CUtils.*;
import static top.birthcat.notitlebar.HitTestHelper.*;
import static top.birthcat.notitlebar.WindowsCall.*;
import static top.birthcat.notitlebar.WindowsConstant.*;

@SuppressWarnings("preview")
public final class NoTitleBar {

    private NoTitleBar() {}

    public static final MethodType JAVA_WND_PROC_TYPE = MethodType.methodType(
            MemorySegment.class, long.class, int.class, MemorySegment.class, MemorySegment.class
    );

    private static MethodHandle WndProcMethod;

    /*
        You muse call NoTitleBar::javaWndProc to handle the other message.
     */
    public static void setWndProcMethod(MethodHandle wndProcMethod) {
        if (!wndProcMethod.type().equals(JAVA_WND_PROC_TYPE)) {
            throw new WrongMethodTypeException(STR."Your type: \{wndProcMethod.type()}, Except type: \{JAVA_WND_PROC_TYPE}");
        }
        WndProcMethod = wndProcMethod;
    }

    /*
        Using default NoTitleBar::composeJavaWndProc to handle the message.
     */
    public static void workWithCompose() {
        try {
            WndProcMethod = MethodHandles.lookup()
                    .findStatic(NoTitleBar.class,"composeJavaWndProc",JAVA_WND_PROC_TYPE);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    /*
        This method will make window invisible and minimized.
     */
    @SuppressWarnings("unused")
    public static boolean tryRemoveIn(Frame frame) {
        if (!System.getProperty("os.name").contains("Windows")) return false;

        try {
            removeIn(frame);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /*
       This method will make window invisible and minimized.
    */
    public static void removeIn(Frame frame) {
        if (!System.getProperty("os.name").contains("Windows")) {
            throw new UnsupportedOperationException("This can't call in non windows system.");
        }

        try {
            final var visible = frame.isVisible();
            // prevent hWnd is null.
            frame.setVisible(true);

            final var state = frame.getState();
            // prevent user see the window change.
            // use minimized icon will show in taskbar.
            frame.setExtendedState(Frame.ICONIFIED);

            // Get hWnd.
            final var peer = (WWindowPeer) WToolkit.targetToPeer(frame);
            final var hWnd = peer.getHWnd();

            replaceWndProc(hWnd);
            extendNonClientArea(hWnd);
            setStyleWithCaption(hWnd);

            // Update window.
            SetWindowPos.invoke(hWnd, 0, 0, 0, 0, 0, FRESH_FLAGS);

            // reset the state
            frame.setVisible(visible);
            frame.setState(state);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private static void setStyleWithCaption(long hWnd) throws Throwable {
        final var style = (MemorySegment) GetWindowLongA.invoke(hWnd, GWL_STYLE);
        SetWindowLongA.invoke(hWnd, GWL_STYLE, toLongPtr(style.address() | WS_CAPTION));
    }

    private static void extendNonClientArea(long hWnd) throws Throwable {
        final var margin = ARENA.allocate(MARGIN);
        margin.set(INT, 0, 1);
        DwmExtendFrameIntoClientArea.invoke(hWnd, margin);
    }

    private static MemorySegment originWndProcAddress;

    private static void replaceWndProc(long hWnd) throws Throwable {

        originWndProcAddress = (MemorySegment) GetWindowLongA.invoke(hWnd, GWL_WNDPROC);

        final var javaWndProc = WndProcMethod != null ? WndProcMethod
                : MethodHandles.lookup().findStatic(NoTitleBar.class, "javaWndProc", JAVA_WND_PROC_TYPE);
        final var wndProcPtr = LINKER.upcallStub(javaWndProc, WndProc, ARENA);
        SetWindowLongA.invoke(hWnd, GWL_WNDPROC, wndProcPtr);
    }

    public static MemorySegment composeJavaWndProc(long hWnd, int uMsg, MemorySegment wParam, MemorySegment lParam) {
        try {

            if (uMsg == WM_NCCALCSIZE) {
                return LRESULT;
            } else {
                return (MemorySegment) CallWindowProcA.invoke(originWndProcAddress, hWnd, uMsg, wParam, lParam);
            }

        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public static MemorySegment javaWndProc(long hWnd, int uMsg, MemorySegment wParam, MemorySegment lParam) {
        try {
            return (MemorySegment) switch (uMsg) {
                /* Ignore WM_NCCALCSIZE */
                case WM_NCCALCSIZE -> LRESULT;
                /*
                    AWT need custom HITTEST
                    But Compose Desktop don't need it.
                */
                case WM_NCHITTEST -> {
                    var point = new HitTestHelper.Point((int) (lParam.address() & 0xffff), (int) (lParam.address() >> 16 & 0xffff));
                    GetWindowRect.invoke(hWnd, RECTPTR);
                    var hitRst = _hitTest(
                            new HitTestHelper.Rect(RECTPTR.getAtIndex(INT, 0),
                                    RECTPTR.getAtIndex(INT, 1),
                                    RECTPTR.getAtIndex(INT, 2),
                                    RECTPTR.getAtIndex(INT, 3)),
                            point
                    );
                    if (hitRst == HTNOWHERE) {
                        yield CallWindowProcA.invoke(originWndProcAddress, hWnd, uMsg, wParam, lParam);
                    } else yield hitRst;
                }
                default -> CallWindowProcA.invoke(originWndProcAddress, hWnd, uMsg, wParam, lParam);
            };
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

}