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
    @Autowired QrRepository qrs; @Autowired StorePosterService posterService; @Autowired StorePosterRepository posters;

    @Test void stickerSheetHasScannableQrAndPdfCarriesTrimBoxes() throws Exception {
        String url="https://field-test.example/s/sticker-token";
        var sheet=ImageIO.read(new ByteArrayInputStream(StorePosterService.stickerSheet("스티커상회",url,null)));
        assertEquals(StorePosterService.SHEET_W,sheet.getWidth());assertEquals(StorePosterService.SHEET_H,sheet.getHeight());
        assertEquals(url,new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(sheet)))).getText());

        try(var pdf=Loader.loadPDF(StorePosterService.printKitPdf("스티커상회",url,null,PosterBrandTheme.FOREST))){
            assertEquals(4,pdf.getNumberOfPages(),"A6 3종 + A4 스티커 판 1쪽");
            assertEquals(105,mm(pdf.getPage(0).getTrimBox().getWidth()),1);assertEquals(148,mm(pdf.getPage(0).getTrimBox().getHeight()),1);
            assertEquals(111,mm(pdf.getPage(0).getMediaBox().getWidth()),1);
            // 스티커는 단품이 아니라 A4 한 판에 10장이다. 단품 1쪽이면 인쇄소가 1장만 뽑는다(실제로 그렇게 나왔다).
            assertEquals(210,mm(pdf.getPage(3).getTrimBox().getWidth()),1);assertEquals(297,mm(pdf.getPage(3).getTrimBox().getHeight()),1);
            assertEquals(210,mm(pdf.getPage(3).getMediaBox().getWidth()),1);
            // 판을 300dpi로 다시 그려 2열×5행 칸을 하나씩 잘라 QR을 읽는다(칸 위치까지 맞아야 통과).
            var page=new org.apache.pdfbox.rendering.PDFRenderer(pdf).renderImageWithDPI(3,300);
            int cellW=StorePosterService.STICKER_W+2*StorePosterService.BLEED,cellH=StorePosterService.STICKER_H+2*StorePosterService.BLEED;
            int left=(page.getWidth()-2*cellW)/2,top=(page.getHeight()-5*cellH)/2;
            for(int i=0;i<StorePosterService.STICKER_COUNT;i++){
                var cell=page.getSubimage(left+(i%2)*cellW,top+(i/2)*cellH,cellW,cellH);
                assertEquals(url,new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(cell)))).getText(),"스티커 "+(i+1));
            }
        }
    }

    @Test void operatorSearchesFiltersAndChangesStatus() throws Exception {
        var p=signup.signUp(new AdminSignupService.Request("secret1234","secret1234","printkit@test.com","키트대표","01055552222",
            "인쇄키트상회","5551110093"),"https://example.test","203.0.113.91");
        String op="Bearer "+jwt.issueOperator(operator("printkit-op@test.com"));
        String owner="Bearer "+jwt.issue(admins.findByEmail("printkit@test.com").orElseThrow());
        Long id=p.store().id;

        mvc.perform(get("/api/operator/print-kits").param("q","인쇄키트").header("Authorization",op))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].storeId").value(id))
            .andExpect(jsonPath("$.data.content[0].status").value("WAITING"));
        // 사업자등록번호는 하이픈을 섞어 넣어도 찾는다.
        mvc.perform(get("/api/operator/print-kits").param("q","555-11-10093").header("Authorization",op))
            .andExpect(jsonPath("$.data.totalElements").value(1));

        mvc.perform(put("/api/operator/print-kits/{id}",id).header("Authorization",op).contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"PRINTING\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.statusUpdatedBy").value("printkit-op@test.com"));
        mvc.perform(get("/api/operator/print-kits").param("q","인쇄키트").param("status","PRINTING").header("Authorization",op))
            .andExpect(jsonPath("$.data.totalElements").value(1));
        mvc.perform(get("/api/operator/print-kits").param("q","인쇄키트").param("status","WAITING").header("Authorization",op))
            .andExpect(jsonPath("$.data.totalElements").value(0));

        mvc.perform(get("/api/operator/print-kits/{id}/pdf",id).header("Authorization",op))
            .andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_PDF));
        // 매장 사장 토큰은 운영자 OTP 세션이 아니라 운영자 필터가 막고, 자기 매장 파일은 직접 받는다.
        mvc.perform(get("/api/operator/print-kits").header("Authorization",owner)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/stores/{id}/print-kit",id).header("Authorization",owner))
            .andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_PDF));
        mvc.perform(get("/api/admin/stores/{id}/sticker-sheet",id).header("Authorization",owner))
            .andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_PNG));
    }

    @Test void onlyOperatorRegeneratesQrAndEverySafeguardHolds() throws Exception {
        var p=signup.signUp(new AdminSignupService.Request("secret1234","secret1234","qrrotate@test.com","회전대표","01055553333",
            "큐알회전상회","5551110094"),"https://example.test","203.0.113.92");
        Long id=p.store().id;
        String op="Bearer "+jwt.issueOperator(operator("qrrotate-op@test.com"));
        String owner="Bearer "+jwt.issue(admins.findByEmail("qrrotate@test.com").orElseThrow());
        String body="{\"confirmName\":\"%s\",\"reason\":\"%s\"}";

        // 사장 API는 없어졌다.
        mvc.perform(post("/api/admin/stores/{id}/qr-codes/regenerate",id).header("Authorization",owner)).andExpect(status().is4xxClientError());
        mvc.perform(post("/api/operator/stores/{id}/qr/regenerate",id).header("Authorization",owner).contentType(MediaType.APPLICATION_JSON)
            .content(body.formatted("큐알회전상회","유출"))).andExpect(status().isUnauthorized());

        // 방금 가입해 QR이 10분 안에 만들어졌다 → 연타·재전송 방지에 걸린다.
        mvc.perform(post("/api/operator/stores/{id}/qr/regenerate",id).header("Authorization",op).contentType(MediaType.APPLICATION_JSON)
            .content(body.formatted("큐알회전상회","유출"))).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("QR_RECENTLY_REGENERATED"));
        StoreQrCode old=qrs.findFirstByStoreIdAndStatus(id,QrStatus.ACTIVE).orElseThrow();
        old.createdAt=clock.instant().minus(java.time.Duration.ofDays(1));qrs.save(old);

        // 매장명이 다르거나 사유가 없으면 아무 것도 바뀌지 않는다.
        mvc.perform(post("/api/operator/stores/{id}/qr/regenerate",id).header("Authorization",op).contentType(MediaType.APPLICATION_JSON)
            .content(body.formatted("큐알회전","유출"))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("QR_REGENERATE_CONFIRM_MISMATCH"));
        mvc.perform(post("/api/operator/stores/{id}/qr/regenerate",id).header("Authorization",op).contentType(MediaType.APPLICATION_JSON)
            .content(body.formatted("큐알회전상회"," "))).andExpect(status().isBadRequest());
        assertEquals(old.publicToken,qrs.findFirstByStoreIdAndStatus(id,QrStatus.ACTIVE).orElseThrow().publicToken);

        // 발송까지 끝난 키트였다면 인쇄 대기로 되돌린다.
        mvc.perform(put("/api/operator/print-kits/{id}",id).header("Authorization",op).contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"SHIPPED\"}")).andExpect(status().isOk());
        posterService.save(p.store(),old.publicToken,"https://field-test.example");
        mvc.perform(post("/api/operator/stores/{id}/qr/regenerate",id).header("Authorization",op).contentType(MediaType.APPLICATION_JSON)
            .content(body.formatted("큐알회전상회","QR 사진이 외부에 퍼짐"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.previousPrintKitStatus").value("SHIPPED")).andExpect(jsonPath("$.data.printKitReset").value(true));

        StoreQrCode now=qrs.findFirstByStoreIdAndStatus(id,QrStatus.ACTIVE).orElseThrow();
        assertNotEquals(old.publicToken,now.publicToken);
        StoreQrCode revoked=qrs.findById(old.id).orElseThrow();
        assertEquals(QrStatus.REVOKED,revoked.status);assertEquals("qrrotate-op@test.com",revoked.revokedByEmail);assertEquals("QR 사진이 외부에 퍼짐",revoked.revokeReason);
        // 사장 안내물 저장본도 새 QR로 바뀐다.
        var image=ImageIO.read(new ByteArrayInputStream(posterService.bytes(posters.findByStoreId(id).orElseThrow())));
        assertTrue(new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)))).getText().endsWith("/s/"+now.publicToken));
        mvc.perform(get("/api/operator/stores/{id}/qr",id).header("Authorization",op))
            .andExpect(jsonPath("$.data.printKitStatus").value("WAITING")).andExpect(jsonPath("$.data.history[0].reason").value("QR 사진이 외부에 퍼짐"));
    }

    @Test void basicPosterIsDrawnFreshSoOldStoredImagesNeverLeak() throws Exception {
        var p=signup.signUp(new AdminSignupService.Request("secret1234","secret1234","fresh-poster@test.com","새그림대표","01055558888",
            "새그림상회","5551110098"),"https://example.test","203.0.113.96");
        Long id=p.store().id;
        String owner="Bearer "+jwt.issue(admins.findByEmail("fresh-poster@test.com").orElseThrow());
        // 저장본을 옛 디자인(다른 그림)으로 바꿔 둔다. 다운로드는 이 이미지가 아니라 지금 코드로 그린 것이어야 한다.
        StorePoster stored=posterService.save(p.store(),p.storeToken(),"https://field-test.example");
        stored.contentBase64=java.util.Base64.getEncoder().encodeToString(StorePosterService.render("옛 디자인","https://field-test.example/s/old"));
        posters.save(stored);

        byte[] downloaded=mvc.perform(get("/api/admin/stores/{id}/poster",id).param("variant","GAME").header("Authorization",owner))
            .andExpect(status().isOk()).andExpect(header().string("X-Poster-Public-Origin","https://field-test.example"))
            .andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(StorePosterService.render(PosterVariant.GAME,"새그림상회","https://field-test.example/s/"+p.storeToken(),null,null),downloaded,
            "기본 안내물도 저장본 origin으로 새로 그린다");
    }

    private static double mm(float pt){return pt*25.4/72;}
    private AdminUser operator(String email){
        AdminUser a=new AdminUser();a.email=email;a.passwordHash=encoder.encode("secret1234");a.name="운영자";
        a.role=AdminRole.OPERATOR;a.createdAt=clock.instant();return admins.save(a);
    }
}
