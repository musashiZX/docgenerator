package com.docgen.api;

import com.docgen.document.DocumentIndexService;
import com.docgen.model.StructuralIndex;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentIndexService documentIndexService;

    public DocumentController(DocumentIndexService documentIndexService) {
        this.documentIndexService = documentIndexService;
    }

    @GetMapping("/{name}/index")
    public StructuralIndex index(@PathVariable("name") String name) throws Exception {
        return documentIndexService.buildIndex(name);
    }
}
