package com.portfolio.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Cross-cutting error handling for the web layer. Splitting one big controller into many
 * left two cases that have to apply to all of them:
 *
 * <ul>
 *   <li>{@link IllegalArgumentException} from form/parameter validation → plain-text 400 so
 *       the JSON-fetching client can display the message inline.</li>
 *   <li>{@link IllegalStateException} from write-path persistence failures → rendered as the
 *       {@code fragments/error} fragment in the same result panel, instead of a stark 500.
 *       Full stack trace still lands in the log.</li>
 * </ul>
 */
@ControllerAdvice
public class GlobalExceptionAdvice {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionAdvice.class);

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseBody
    public ResponseEntity<String> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public String handlePersistenceFailure(IllegalStateException e, Model model) {
        log.warn("Action failed", e);
        model.addAttribute("errorMessage", e.getMessage());
        model.addAttribute("completedAt", WebSupport.now());
        return "fragments/error :: result";
    }
}
