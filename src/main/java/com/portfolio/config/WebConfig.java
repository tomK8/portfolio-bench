package com.portfolio.config;

import com.portfolio.application.CatchUpService;
import com.portfolio.web.DormancyInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC wiring. Just the one hook: every request tells {@link CatchUpService} the app is in use, so
 * a process that has been dormant through a long sleep repairs itself at the moment you come back
 * to it instead of waiting for jobs that cannot backfill what they missed.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final CatchUpService catchUp;

    public WebConfig(CatchUpService catchUp) {
        this.catchUp = catchUp;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new DormancyInterceptor(catchUp))
                .addPathPatterns("/**")
                // Static assets only: one page load pulls a dozen of them and none says anything
                // about the app being used that the page's own request has not already said.
                .excludePathPatterns("/js/**");
    }
}
