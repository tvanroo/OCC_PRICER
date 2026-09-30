package com.cardpricer.cloud.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Serves the React app for client-side routes; static assets are served directly. */
@Controller
public class SpaController {
    @GetMapping({"/", "/login", "/signup", "/app", "/app/**"})
    public String index() {
        return "forward:/index.html";
    }
}
