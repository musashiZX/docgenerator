package com.docgen.onlyoffice;

import com.docgen.document.DocumentLoader;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Read-only: surfaces comments left in the live OnlyOffice editor as
 * AI-focus candidates. See {@link CommentFocusReader} for why comments
 * (not a custom plugin) are the mechanism.
 */
@RestController
@RequestMapping("/api/onlyoffice")
public class OnlyOfficeCommentsController {

    private final DocumentLoader documentLoader;

    public OnlyOfficeCommentsController(DocumentLoader documentLoader) {
        this.documentLoader = documentLoader;
    }

    @GetMapping("/comments/{name}")
    public Map<String, Object> comments(@PathVariable("name") String name) throws Exception {
        WordprocessingMLPackage document = documentLoader.load(documentLoader.resolveDoc(name));
        List<CommentFocusReader.CommentFocus> comments = CommentFocusReader.read(document);
        return Map.of("comments", comments);
    }
}
