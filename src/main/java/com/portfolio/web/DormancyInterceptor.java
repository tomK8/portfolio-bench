package com.portfolio.web;

import com.portfolio.application.CatchUpService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Tells {@link CatchUpService} that somebody is using the app again.
 *
 * <p>Sitting on every request rather than on the Live tab alone is what makes the recovery
 * "across the board": whichever panel you open first after the laptop has been shut for two days
 * is the one that starts the repair, and by the time you reach the others it is already running.
 * It costs a volatile read and a subtraction per request — the gate does nothing else unless it
 * is actually open — which is the whole reason this is a hook on the way in and not a poller.
 */
public class DormancyInterceptor implements HandlerInterceptor {

    private final CatchUpService catchUp;

    public DormancyInterceptor(CatchUpService catchUp) {
        this.catchUp = catchUp;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        catchUp.noteActivity();
        return true;
    }
}
