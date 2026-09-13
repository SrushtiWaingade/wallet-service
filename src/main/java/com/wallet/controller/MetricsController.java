package com.wallet.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

// The brief names /metrics; Actuator serves the scrape at /actuator/prometheus.
// Forwarding rather than redirecting means a scraper gets the payload from the
// path it asked for, without following a 302 or a second request.
@Controller
public class MetricsController {

    @GetMapping("/metrics")
    public String scrape() {
        return "forward:/actuator/prometheus";
    }
}
