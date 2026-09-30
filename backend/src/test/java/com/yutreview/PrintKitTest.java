package com.yutreview;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/** 입점 키트: 스티커·PDF 파일 규격, 운영자 검색·상태 필터·상태 변경, 사장 다운로드. */
@SpringBootTest @AutoConfigureMockMvc
class PrintKitTest {
    @Autowired MockMvc mvc; @Autowired AdminSignupService signup; @Autowired AdminUserRepository admins;
    @Autowired JwtService jwt; @Autowired PasswordEncoder encoder; @Autowired Clock clock;

    @Test void stickerSheetHasScannableQrAndPdfCarriesTrimBoxes() throws Exception {
        String url="https://field-test.example/s/sticker-token";
        var sheet=ImageIO.read(new ByteArrayInputStream(StorePosterService.stickerSheet("스티커상회",url,null)));
        assertEquals(StorePosterService.SHEET_W,sheet.getWidth());assertEquals(StorePosterService.SHEET_H,sheet.getHeight());
        assertEquals(url,new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(sheet)))).getText());

        try(var pdf=Loader.loadPDF(StorePosterService.printKitPdf("스티커상회",url,null,PosterBrandTheme.FOREST))){
            assertEquals(4,pdf.getNumberOfPages(),"A6 3종 + 스티커 1쪽");
            assertEquals(105,mm(pdf.getPage(0).getTrimBox().getWidth()),1);assertEquals(148,mm(pdf.getPage(0).getTrimBox().getHeight()),1);
            assertEquals(111,mm(pdf.getPage(0).getMediaBox().getWidth()),1);
            assertEquals(90,mm(pdf.getPage(3).getTrimBox().getWidth()),1);assertEquals(50,mm(pdf.getPage(3).getTrimBox().getHeight()),1);
            assertEquals(96,mm(pdf.getPage(3).getBleedBox().getWidth()),1);
        }
    }

    @Test void operatorSearchesFiltersAndChangesStatus() throws Exception {
        var p=signup.signUp(new AdminSignupService.Request("secret1234","secret1234","printkit@test.com","키트대표","01055552222",
            "인쇄키트상회","5551110093"),"https://example.test","203.0.113.91");
        String op="Bearer "+jwt.issueOperator(operator("printkit-op@test.com"));
        String owner="Bearer "+jwt.issue(admins.findByEmail("printkit@test.com").orElseThrow());
        Long id=p.store().id;

        mvc.perform(get("/api/admin/operator/print-kits").param("q","인쇄키트").header("Authorization",op))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].storeId").value(id))
            .andExpect(jsonPath("$.data.content[0].status").value("WAITING"));
        // 사업자등록번호는 하이픈을 섞어 넣어도 찾는다.
        mvc.perform(get("/api/admin/operator/print-kits").param("q","555-11-10093").header("Authorization",op))
            .andExpect(jsonPath("$.data.totalElements").value(1));

        mvc.perform(put("/api/admin/operator/print-kits/{id}",id).header("Authorization",op).contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"PRINTING\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.statusUpdatedBy").value("printkit-op@test.com"));
        mvc.perform(get("/api/admin/operator/print-kits").param("q","인쇄키트").param("status","PRINTING").header("Authorization",op))
            .andExpect(jsonPath("$.data.totalElements").value(1));
        mvc.perform(get("/api/admin/operator/print-kits").param("q","인쇄키트").param("status","WAITING").header("Authorization",op))
            .andExpect(jsonPath("$.data.totalElements").value(0));

        mvc.perform(get("/api/admin/operator/print-kits/{id}/pdf",id).header("Authorization",op))
            .andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_PDF));
        // 매장 사장 토큰은 운영자 OTP 세션이 아니라 운영자 필터가 막고, 자기 매장 파일은 직접 받는다.
        mvc.perform(get("/api/admin/operator/print-kits").header("Authorization",owner)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/stores/{id}/print-kit",id).header("Authorization",owner))
            .andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_PDF));
        mvc.perform(get("/api/admin/stores/{id}/sticker-sheet",id).header("Authorization",owner))
            .andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_PNG));
    }

    private static double mm(float pt){return pt*25.4/72;}
    private AdminUser operator(String email){
        AdminUser a=new AdminUser();a.email=email;a.passwordHash=encoder.encode("secret1234");a.name="운영자";
        a.role=AdminRole.SYSTEM_ADMIN;a.createdAt=clock.instant();return admins.save(a);
    }
}
