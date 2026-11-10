package com.germanreader.library;

import com.germanreader.library.BuiltinLibraryService.BuiltinTextResponse;
import com.germanreader.library.BuiltinLibraryService.BuiltinTextSummary;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/library")
public class BuiltinLibraryController {

    private final BuiltinLibraryService service;

    public BuiltinLibraryController(BuiltinLibraryService service) {
        this.service = service;
    }

    @GetMapping
    public List<BuiltinTextSummary> list(@RequestParam(required = false) CefrLevel level) {
        return service.list(level);
    }

    @GetMapping("/{id}")
    public BuiltinTextResponse get(@PathVariable UUID id) {
        return service.get(id);
    }
}
