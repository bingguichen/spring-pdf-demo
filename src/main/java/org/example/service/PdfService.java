package org.example.service;

import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.xhtmlrenderer.pdf.ITextFontResolver;
import org.xhtmlrenderer.pdf.ITextRenderer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

@Service
public class PdfService {
    @Autowired
    private TemplateEngine templateEngine;

    /**
     * 生成HTML内容
     * @return 渲染后的HTML字符串
     */
    public String generateHtmlContent() {
        Context context = new Context();
        context.setVariable("title", "PDF文档标题");
        context.setVariable("content",
                "这是PDF文档的主要内容："
                        + "“成功并不是终点，失败也不是终结，最重要的是继续前行的勇气。在人生的旅途中，我们会遇到许多挑战与挫折，这些都是成长的必经之路。每一次跌倒都是一次学习的机会，每一次失败都为成功铺设了基础。只要我们保持信念，不断努力，最终会到达梦想的彼岸。无论前方的路有多么坎坷，只要心怀希望，我们就有无限的可能性去改变自己的命运，实现心中的理想。”"
                        + "由Thymeleaf模板引擎渲染。");
        return templateEngine.process("pdf_template", context);
    }

    /**
     * 将HTML内容转换为PDF并写入响应
     * @param response HTTP响应
     * @param htmlContent HTML内容
     * @throws IOException IO异常
     * @throws DocumentException 文档异常
     */
    public void generatePdf(HttpServletResponse response, String htmlContent) throws IOException, DocumentException {
        // 设置响应类型
        response.setContentType("application/pdf");
        response.setHeader("Content-Disposition", "attachment; filename=generated.pdf");

        // 创建ITextRenderer实例并注册字体（必须在渲染前注册）
        ITextRenderer renderer = new ITextRenderer();
        ITextFontResolver fontResolver = renderer.getFontResolver();
        ClassPathResource fontResource = new ClassPathResource("fonts/SimSun.ttf");
        fontResolver.addFont(fontResource.getFile().getAbsolutePath(), "Identity-H", true);

        // 为页眉页脚准备字体（优先使用 SimSun）
        final com.lowagie.text.Font headerFooterFont;
        Font headerFooterFont1;
        String simSunPath = fontResource.getFile().getAbsolutePath();
        try {
            BaseFont bf = BaseFont.createFont(simSunPath, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
            headerFooterFont1 = new Font(bf, 10);
        } catch (Exception e) {
            headerFooterFont1 = FontFactory.getFont(FontFactory.HELVETICA, 10);
        }
        headerFooterFont = headerFooterFont1;

        // 渲染 HTML 到临时字节数组
        renderer.setDocumentFromString(htmlContent);
        renderer.layout();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        renderer.createPDF(baos);
        renderer.finishPDF();
        byte[] pdfBytes = baos.toByteArray();

        // 使用 PdfStamper 对每一页进行叠加（添加页眉页脚）
        try (OutputStream outputStream = response.getOutputStream()) {
            PdfReader reader = new PdfReader(pdfBytes);
            PdfStamper stamper = new PdfStamper(reader, outputStream);

            int total = reader.getNumberOfPages();
            for (int i = 1; i <= total; i++) {
                Rectangle pageSize = reader.getPageSize(i);
                float centerX = (pageSize.getLeft() + pageSize.getRight()) / 2;
                float headerY = pageSize.getTop() - 20;
                float footerY = pageSize.getBottom() + 20;

                PdfContentByte cb = stamper.getOverContent(i);
                // 页眉
                Phrase header = new Phrase("示例文档页眉", headerFooterFont);
                ColumnText.showTextAligned(cb, Element.ALIGN_CENTER, header, centerX, headerY, 0);
                // 页脚（页码）
                String footerText = String.format("第 %d 页", i);
                Phrase footer = new Phrase(footerText, headerFooterFont);
                ColumnText.showTextAligned(cb, Element.ALIGN_CENTER, footer, centerX, footerY, 0);
            }

            stamper.close();
            reader.close();
            outputStream.flush();
        }
    }

}
