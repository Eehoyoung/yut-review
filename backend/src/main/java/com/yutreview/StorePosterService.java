package com.yutreview;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.springframework.stereotype.Service;

/** 안내물 종류. GAME이 기본이며 저장되는 것도 GAME뿐이다. 나머지는 요청할 때 그린다. 입점 키트에는 넷 다 들어간다. */
enum PosterVariant { GAME, EVENT, REVISIT, REVIEW }

/**
 * 입점 키트 = A6 안내물 4종 + 테이블 스티커(90×50mm) 10장.
 *
 * 모든 치수는 300dpi 픽셀이다(1mm ≈ 11.8px, 1pt ≈ 4.17px). 화면·사장 다운로드용 PNG는 재단 크기 그대로,
 * 인쇄소용 PDF는 사방 3mm 도련을 붙여 같은 함수로 그린다. 배경과 하단 띠만 도련까지 번지고
 * 글자는 모두 재단선 안쪽 3mm(35px) 안전선 안에 있다.
 */
@Service class StorePosterService {
    static final int WIDTH=1240,HEIGHT=1748;          // A6 105×148mm
    static final int STICKER_W=1063,STICKER_H=591;   // 90×50mm, 명함 스티커 규격
    static final int SHEET_W=2480,SHEET_H=3508;      // A4, 사장이 직접 뽑는 스티커 10칸 판
    static final int BLEED=35;                       // 3mm
    static final int STICKER_COUNT=10;
    private static final double DPI=300;

    // DESIGN.md 팔레트(소담랩스 로고 색). 밝은 바탕 위 글자·채움은 딥 오렌지, 네이비 위 강조만 로고 원색.
    private static final Color NAVY=new Color(0x162436),PAPER=Color.WHITE,ORANGE=new Color(0xFC672D),ORANGE_DEEP=new Color(0xC14A15),
        CREAM=new Color(0xFCDAC2),YELLOW=new Color(0xFFE27A),WOOD=new Color(0x9A5B2A),WOOD_LIGHT=new Color(0xD9A873),MUTED=new Color(0x4C5A6B),
        NAVER_GREEN=new Color(0x03C75A),FOREST_INK=new Color(0x0F3A25);
    // 큰 제목만 주아체(귀여움), 나머지는 Pretendard. 둘 다 OFL이고 번들한다 — 시스템 글꼴에 맡기면
    // 운영 이미지와 개발 PC가 서로 다른 글꼴로 그려서 자간까지 달라졌다.
    private static final Font CUTE=load("/fonts/Jua-Regular.ttf"),BOLD=load("/fonts/Pretendard-Bold.ttf"),REGULAR=load("/fonts/Pretendard-Regular.ttf");
    private static Font load(String path){
        try(var in=StorePosterService.class.getResourceAsStream(path)){return Font.createFont(Font.TRUETYPE_FONT,in);}
        catch(Exception e){throw new IllegalStateException("안내물 글꼴을 읽지 못했습니다: "+path,e);}
    }
    private final StorePosterRepository posters;private final Clock clock;
    StorePosterService(StorePosterRepository posters,Clock clock){this.posters=posters;this.clock=clock;}

    StorePoster save(Store store,String storeToken,String publicOrigin){
        return save(store,storeToken,publicOrigin,null);
    }

    /**
     * 안내물 저장. tagline은 PRO에서만 채워져 들어온다(브랜딩 권한).
     * 권한 판단은 호출부가 하고 여기서는 받은 값을 그릴 뿐이다.
     */
    StorePoster save(Store store,String storeToken,String publicOrigin,String tagline){
        String origin=origin(publicOrigin),url=origin+"/s/"+storeToken;
        StorePoster poster=posters.findByStoreId(store.id).orElseGet(StorePoster::new);Instant now=clock.instant();
        if(poster.id==null){poster.store=store;poster.createdAt=now;}
        poster.contentBase64=Base64.getEncoder().encodeToString(render(PosterVariant.GAME,store.name,url,tagline,store.posterBrandTheme));poster.publicOrigin=origin;poster.updatedAt=now;
        return posters.save(poster);
    }

    byte[] bytes(StorePoster poster){return Base64.getDecoder().decode(poster.contentBase64);}

    private static String origin(String value){
        try{URI uri=URI.create(value);if((!"http".equals(uri.getScheme())&&!"https".equals(uri.getScheme()))||uri.getHost()==null||uri.getUserInfo()!=null)throw new IllegalArgumentException();return uri.getScheme()+"://"+uri.getRawAuthority();}
        catch(IllegalArgumentException e){throw new AppException("INVALID_PUBLIC_ORIGIN","공개 접속 주소가 올바르지 않습니다.");}
    }

    static byte[] render(String storeName,String url){return render(storeName,url,null);}
    static byte[] render(String storeName,String url,String tagline){return render(PosterVariant.GAME,storeName,url,tagline);}
    static byte[] render(PosterVariant variant,String storeName,String url,String tagline){return render(variant,storeName,url,tagline,null);}
    /** 재단 크기 A6 PNG. 화면 미리보기·사장 저장·저장본이 모두 이것이다. */
    static byte[] render(PosterVariant variant,String storeName,String url,String tagline,PosterBrandTheme brand){
        return png(poster(variant,storeName,url,tagline,Palette.of(variant,brand),0));
    }

    /** A4 한 장에 스티커 10칸(2×5). 칸 사이 4mm 여백과 회색 재단선. 사장이 라벨지나 일반 용지에 직접 뽑는 용도. */
    static byte[] stickerSheet(String storeName,String url,PosterBrandTheme brand){
        BufferedImage sticker=sticker(storeName,url,Palette.of(PosterVariant.GAME,brand),0);
        BufferedImage sheet=new BufferedImage(SHEET_W,SHEET_H,BufferedImage.TYPE_INT_RGB);Graphics2D g=start(sheet);
        g.setColor(PAPER);g.fillRect(0,0,SHEET_W,SHEET_H);
        int gap=47,left=(SHEET_W-2*STICKER_W-gap)/2,top=(SHEET_H-5*STICKER_H-4*gap)/2;
        g.setColor(MUTED);g.setFont(REGULAR.deriveFont(30f));
        center(g,storeName+" · 테이블 스티커 "+STICKER_COUNT+"장 · 90×50mm · 회색 선을 따라 잘라 주세요",SHEET_W/2,top-40);
        g.setColor(new Color(0xB8BEC6));g.setStroke(new BasicStroke(2));
        for(int i=0;i<STICKER_COUNT;i++){int x=left+(i%2)*(STICKER_W+gap),y=top+(i/2)*(STICKER_H+gap);
            g.drawImage(sticker,x,y,null);g.drawRect(x-1,y-1,STICKER_W+1,STICKER_H+1);}
        g.dispose();return png(sheet);
    }

    /**
     * 인쇄소용 입점 키트 PDF. A6 4쪽(기본·이벤트·재방문·네이버 리뷰, 각 도련 3mm, TrimBox=재단선) + A4 스티커 판 1쪽(10장, 재단 표시).
     * 스티커를 단품 1쪽으로 두면 수량을 파일명으로만 전해야 해서 1장만 인쇄되는 일이 생긴다. 판 그대로 10장이 찍힌다.
     * 색은 RGB다. CMYK 변환은 인쇄소 RIP에 맡긴다(오렌지가 약간 가라앉는다).
     */
    static byte[] printKitPdf(String storeName,String url,String tagline,PosterBrandTheme brand){
        try(PDDocument doc=new PDDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            for(PosterVariant v:PosterVariant.values())addPage(doc,poster(v,storeName,url,tagline,Palette.of(v,brand),BLEED),BLEED);
            addPage(doc,stickerPrintSheet(storeName,url,Palette.of(PosterVariant.GAME,brand)),0);
            doc.getDocumentInformation().setTitle(storeName+" 입점 키트 — A6 안내물 "+PosterVariant.values().length+"종 각 1매, 테이블 스티커 90×50mm "+STICKER_COUNT+"매");
            doc.getDocumentInformation().setCreator("소담한판");
            doc.save(out);return out.toByteArray();
        }catch(IOException e){throw new IllegalStateException("인쇄용 PDF 생성에 실패했습니다.",e);}
    }
    /** bleed: 이미지 가장자리에서 재단선까지의 거리(px). A4 스티커 판은 각 스티커가 자기 도련을 가지므로 0(쪽 전체가 재단 기준). */
    private static void addPage(PDDocument doc,BufferedImage image,int bleed) throws IOException{
        float w=pt(image.getWidth()),h=pt(image.getHeight()),b=pt(bleed);
        PDPage page=new PDPage(new PDRectangle(w,h));page.setBleedBox(new PDRectangle(w,h));page.setTrimBox(new PDRectangle(b,b,w-2*b,h-2*b));
        var xobject=LosslessFactory.createFromImage(doc,image);
        try(var cs=new PDPageContentStream(doc,page)){cs.drawImage(xobject,0,0,w,h);}
        doc.addPage(page);
    }
    private static float pt(int px){return (float)(px*72/DPI);}

    /**
     * 인쇄소용 A4 한 판: 90×50mm 스티커 10장(2열×5행). 스티커마다 사방 3mm 도련을 붙여 서로 맞닿게 놓고
     * (재단선 사이 6mm), 바깥 여백에 재단 표시를 그린다. 재단이 조금 어긋나도 흰 테두리가 생기지 않는다.
     * 사장이 가위로 자르는 판(`stickerSheet`)은 도련 대신 회색 재단선을 그린 별도 그림이다.
     */
    private static BufferedImage stickerPrintSheet(String storeName,String url,Palette p){
        BufferedImage one=sticker(storeName,url,p,BLEED);
        int cellW=one.getWidth(),cellH=one.getHeight(),left=(SHEET_W-2*cellW)/2,top=(SHEET_H-5*cellH)/2;
        BufferedImage sheet=new BufferedImage(SHEET_W,SHEET_H,BufferedImage.TYPE_INT_RGB);Graphics2D g=start(sheet);
        g.setColor(PAPER);g.fillRect(0,0,SHEET_W,SHEET_H);
        for(int i=0;i<STICKER_COUNT;i++)g.drawImage(one,left+(i%2)*cellW,top+(i/2)*cellH,null);
        // 재단 표시: 도련 바깥 1mm 떨어져 5mm 길이. 재단선 위치마다 위·아래(세로선), 왼·오른쪽(가로선) 여백에 그린다.
        g.setColor(Color.BLACK);g.setStroke(new BasicStroke(2));int gap=12,len=59,right=left+2*cellW,bottom=top+5*cellH;
        for(int c=0;c<2;c++)for(int x:new int[]{left+c*cellW+BLEED,left+c*cellW+BLEED+STICKER_W}){
            g.drawLine(x,top-gap-len,x,top-gap);g.drawLine(x,bottom+gap,x,bottom+gap+len);}
        for(int r=0;r<5;r++)for(int y:new int[]{top+r*cellH+BLEED,top+r*cellH+BLEED+STICKER_H}){
            g.drawLine(left-gap-len,y,left-gap,y);g.drawLine(right+gap,y,right+gap+len,y);}
        g.dispose();return sheet;
    }

    /**
     * 네 안내물은 같은 뼈대(상호 → 제목 두 줄 → 보조문 → QR 판 → 참여 3단계 → 하단 띠)이고 가운데 축 하나로 정렬한다.
     * 색·장식·문구만 다르다. QR 판의 크기와 위치가 모두 같아서 어느 것을 붙여도 스캔 거리가 같다.
     * tagline은 매장이 직접 쓴 한 줄이며 PRO에서만 채워진다. 없으면 종류별 기본 보조문을 쓴다.
     * REVIEW만 "네이버 리뷰 → 윷 → 상품" 순서를 적는다(사용자 결정 2026-10-01). 별점·내용은 조건이 아님을 하단에 밝힌다.
     */
    private static BufferedImage poster(PosterVariant variant,String storeName,String url,String tagline,Palette p,int bleed){
        BufferedImage image=new BufferedImage(WIDTH+2*bleed,HEIGHT+2*bleed,BufferedImage.TYPE_INT_RGB);Graphics2D g=start(image);
        g.translate(bleed,bleed);
        g.setPaint(new GradientPaint(0,0,p.bgTop,0,HEIGHT,p.bgBottom));g.fillRect(-bleed,-bleed,WIDTH+2*bleed,HEIGHT+2*bleed);
        decorate(g,variant,p);
        Copy c=Copy.of(variant);

        // 상호: 한 줄에 안 들어가면 44px까지 줄이고, 그래도 넘치면 어절 단위로 두 줄.
        Block name=block(g,storeName,BOLD,60,44,560,2);FontMetrics nm=g.getFontMetrics(name.font);
        int nameW=name.lines.stream().mapToInt(nm::stringWidth).max().orElse(0),lineH=nm.getHeight();
        int pillW=nameW+130,pillH=name.lines.size()==1?96:40+lineH*name.lines.size(),pillX=(WIDTH-pillW)/2;
        g.setColor(p.pill);g.fillRoundRect(pillX,96,pillW,pillH,96,96);
        g.setColor(p.dot);g.fillOval(pillX+44,96+pillH/2-11,22,22);
        g.setColor(p.pillInk);g.setFont(name.font);
        int nameTop=96+(pillH-lineH*name.lines.size())/2;
        for(int i=0;i<name.lines.size();i++)g.drawString(name.lines.get(i),pillX+86,nameTop+i*lineH+nm.getAscent());

        g.setColor(p.ink);g.setFont(fit(g,c.line1,CUTE,96,64,1000));center(g,c.line1,WIDTH/2,372);
        g.setColor(p.accent);g.setFont(fit(g,c.line2,CUTE,132,88,1048));center(g,c.line2,WIDTH/2,520);
        String subText=tagline==null||tagline.isBlank()?c.sub:tagline.trim();
        Block sub=block(g,subText,REGULAR,44,36,1000,2);g.setColor(p.subInk);g.setFont(sub.font);
        for(int i=0;i<sub.lines.size();i++)center(g,sub.lines.get(i),WIDTH/2,600+i*54);

        int plateX=230,plateY=700,plateW=780,plateH=700;
        g.setColor(p.ring);g.fillRoundRect(plateX-12,plateY-12,plateW+24,plateH+24,80,80);
        g.setColor(PAPER);g.fillRoundRect(plateX,plateY,plateW,plateH,64,64);
        g.setColor(NAVY);g.setFont(BOLD.deriveFont(44f));center(g,"휴대폰 카메라로 비춰 주세요",WIDTH/2,776);
        drawQr(g,url,342,810,555);

        // 3단계는 96~1144 폭에 같은 간격으로 나눈다. 글자 폭이 달라도 좌우 여백이 같다.
        String[] steps=c.steps;Font stepFont=BOLD.deriveFont(44f);FontMetrics sm=g.getFontMetrics(stepFont);
        int[] widths=new int[3];int total=0;for(int i=0;i<3;i++){widths[i]=64+16+sm.stringWidth(steps[i]);total+=widths[i];}
        int gap=(1048-total)/2,x=96;
        for(int i=0;i<3;i++){
            g.setColor(p.stepDot);g.fillOval(x,1440,64,64);
            g.setColor(p.stepDotInk);g.setFont(stepFont);center(g,Integer.toString(i+1),x+32,1488);
            g.setColor(p.ink);g.drawString(steps[i],x+80,1488);x+=widths[i]+gap;}

        g.setColor(p.band);g.fillRect(-bleed,1544,WIDTH+2*bleed,HEIGHT-1544+bleed);
        g.setColor(p.bandInk);g.setFont(fit(g,"앱 설치 없이 이름과 번호만 입력하면 돼요",BOLD,46,36,1048));center(g,"앱 설치 없이 이름과 번호만 입력하면 돼요",WIDTH/2,1618);
        String foot=c.footnote+"  ·  소담한판";
        g.setColor(p.bandSub);g.setFont(fit(g,foot,REGULAR,34,30,1048));center(g,foot,WIDTH/2,1674);
        g.dispose();return image;
    }

    /** 테이블 스티커 90×50mm. 왼쪽 QR 판, 오른쪽 상호·제목·안내. 앉은 자리(30~60cm)에서 찍는 크기라 QR은 약 32mm면 충분하다. */
    private static BufferedImage sticker(String storeName,String url,Palette p,int bleed){
        BufferedImage image=new BufferedImage(STICKER_W+2*bleed,STICKER_H+2*bleed,BufferedImage.TYPE_INT_RGB);Graphics2D g=start(image);
        g.translate(bleed,bleed);
        g.setPaint(new GradientPaint(0,0,p.bgTop,0,STICKER_H,p.bgBottom));g.fillRect(-bleed,-bleed,STICKER_W+2*bleed,STICKER_H+2*bleed);

        g.setColor(p.ring);g.fillRoundRect(40,45,500,500,56,56);
        g.setColor(PAPER);g.fillRoundRect(48,53,484,484,48,48);
        drawQr(g,url,60,65,460);

        int x=580,maxW=STICKER_W-BLEED-x;
        g.setColor(p.dot);g.fillOval(x,86,20,20);
        g.setColor(p.ink);g.setFont(BOLD.deriveFont(34f));String name=ellipsize(g.getFontMetrics(),storeName,maxW-32);
        g.setFont(fit(g,name,BOLD,34,30,maxW-32));g.drawString(name,x+32,108);
        g.setColor(p.ink);g.setFont(fit(g,"윷 한 판 던지고",CUTE,62,48,maxW));g.drawString("윷 한 판 던지고",x,210);
        g.setColor(p.accent);g.setFont(fit(g,"쿠폰 받아 가세요",CUTE,62,48,maxW));g.drawString("쿠폰 받아 가세요",x,288);
        g.setColor(p.subInk);g.setFont(fit(g,"카메라로 비추면 바로 시작해요",REGULAR,31,30,maxW));
        g.drawString("카메라로 비추면 바로 시작해요",x,370);g.drawString("앱 설치 없이 참여해요",x,414);
        g.setColor(p.subInk);g.setFont(BOLD.deriveFont(30f));g.drawString("소담한판",x,528);
        Graphics2D d=(Graphics2D)g.create();d.translate(950,470);d.scale(.38,.38);
        stick(d,-70,0,-18,p.stickA,true);stick(d,20,-10,8,p.stickB,false);stick(d,110,0,24,p.stickC,true);d.dispose();
        g.dispose();return image;
    }

    /** 안내물 종류별 문구. 매장이 바꿀 수 있는 것은 PRO의 한 줄 소개뿐이다. */
    private record Copy(String line1,String line2,String sub,String footnote,String[] steps){
        private static final String[] PLAY={"QR 비추기","윷 던지기","쿠폰 받기"};
        static Copy of(PosterVariant v){return switch(v){
            case GAME->new Copy("윷 한 판 던지고","쿠폰 받아 가세요","도·개·걸·윷·모, 무엇이 나와도 쿠폰을 드려요","쿠폰 쓰는 방법은 결과 화면에서 알려 드려요",PLAY);
            case EVENT->new Copy("만나서 반가워요","깜짝 선물 받아 가세요","윷 한 번 던지면 오늘의 선물이 정해져요","쿠폰 쓰는 방법은 결과 화면에서 알려 드려요",PLAY);
            case REVISIT->new Copy("오늘 즐거우셨나요?","감사 선물 받아 가세요","오늘 던지셨다면 모레 또 던질 수 있어요","쓰지 않은 쿠폰이 있으면 그 쿠폰부터 보여 드려요",PLAY);
            case REVIEW->new Copy("네이버 리뷰 남기고","윷 던져 상품 받기","리뷰를 쓰신 뒤 QR을 비추면 바로 윷을 던져요","별점·내용과 관계없이 누구나 참여할 수 있어요",new String[]{"네이버 리뷰","윷 던지기","상품 당첨"});
        };}
    }

    /**
     * 색. 종류(GAME 크림 · EVENT 딥 오렌지 · REVISIT 네이비)마다 바탕이 다르고, PRO 브랜드 테마(FOREST·PLUM)를
     * 고르면 종류와 관계없이 그 테마의 밝은 바탕으로 **전부** 바뀐다. 예전에는 아래쪽 바탕색과 장식이 종류 값으로
     * 남아 민트→오렌지처럼 섞였다. 대비는 본문 4.5:1, 큰 제목 3:1 이상으로 맞췄다.
     */
    private record Palette(Color bgTop,Color bgBottom,Color ink,Color accent,Color subInk,Color pill,Color pillInk,Color dot,Color ring,
                           Color stepDot,Color stepDotInk,Color band,Color bandInk,Color bandSub,Color blob,Color stickA,Color stickB,Color stickC,Color[] confetti){
        static Palette of(PosterVariant v,PosterBrandTheme brand){
            if(brand==PosterBrandTheme.FOREST)return brand(new Color(0x174C3C),new Color(0x267A5B),new Color(0xDDEFE6),new Color(0xC6E4D5));
            if(brand==PosterBrandTheme.PLUM)return brand(new Color(0x512A4B),new Color(0x8A3E72),new Color(0xF3DFEB),new Color(0xE8C9DC));
            return switch(v){
                case GAME->new Palette(new Color(0xFFF8F0),new Color(0xFFE4CE),NAVY,ORANGE_DEEP,MUTED,NAVY,PAPER,ORANGE,ORANGE,
                    ORANGE_DEEP,PAPER,NAVY,PAPER,CREAM,new Color(0xFFE2CB),WOOD_LIGHT,ORANGE,WOOD,null);
                case EVENT->new Palette(ORANGE_DEEP,new Color(0xA83C12),PAPER,YELLOW,new Color(0xFFF1E6),PAPER,NAVY,ORANGE,NAVY,
                    PAPER,ORANGE_DEEP,NAVY,PAPER,CREAM,null,YELLOW,PAPER,WOOD_LIGHT,new Color[]{PAPER,YELLOW,NAVY,CREAM});
                case REVISIT->new Palette(new Color(0x22364F),NAVY,PAPER,ORANGE,CREAM,CREAM,NAVY,ORANGE,ORANGE,
                    ORANGE,NAVY,ORANGE_DEEP,PAPER,PAPER,new Color(0x2A405C),WOOD_LIGHT,ORANGE,WOOD,null);
                // 네이버 그린 계열. 원색(#03C75A)은 밝은 바탕에서 3:1이 안 나와 장식·테두리에만 쓰고 글자는 짙은 초록.
                case REVIEW->new Palette(new Color(0xF1FBF4),new Color(0xD3F2DE),FOREST_INK,new Color(0x00813A),new Color(0x3E5A4B),FOREST_INK,PAPER,NAVER_GREEN,NAVER_GREEN,
                    new Color(0x00813A),PAPER,FOREST_INK,PAPER,new Color(0xBFEBCF),new Color(0xDDF5E6),WOOD_LIGHT,NAVER_GREEN,WOOD,null);
            };
        }
        private static Palette brand(Color dark,Color strong,Color soft,Color deeper){
            return new Palette(soft,deeper,dark,strong,dark,dark,PAPER,soft,strong,strong,PAPER,dark,PAPER,soft,deeper,WOOD_LIGHT,strong,WOOD,
                new Color[]{strong,dark,WOOD_LIGHT,PAPER});
        }
    }

    // 장식은 네 모서리에만 둔다. 가운데 축(상호·제목·QR 판)과 겹치지 않는다. 좌표를 고정해 같은 매장은 늘 같은 그림이다.
    private static void decorate(Graphics2D g,PosterVariant v,Palette p){
        boolean confetti=v==PosterVariant.EVENT&&p.confetti!=null;
        if(p.blob!=null&&!confetti){g.setColor(p.blob);g.fillOval(-150,-180,440,440);g.fillOval(950,-180,440,440);}
        if(confetti){
            int[][] bits={{60,70,0},{250,50,2},{50,300,3},{1000,60,2},{1190,70,3},{1190,300,1},
                {70,730,1},{160,870,3},{80,1020,0},{170,1170,2},{70,1330,3},{1080,740,2},{1180,900,0},{1100,1060,3},{1190,1210,1},{1090,1350,0}};
            for(int[] b:bits){AffineTransform old=g.getTransform();g.translate(b[0],b[1]);g.rotate(Math.toRadians(b[0]*7%90-45));g.setColor(p.confetti[b[2]]);
                if(b[2]%2==0)g.fillRoundRect(-24,-10,48,20,10,10);else g.fillOval(-15,-15,30,30);g.setTransform(old);}
        }
        if(v==PosterVariant.REVIEW){bubble(g,170,170,p);stick(g,1030,165,-12,p.stickA,true);stick(g,1120,175,18,p.stickB,false);}
        else if(v==PosterVariant.GAME){stick(g,120,165,-24,p.stickA,true);stick(g,212,150,8,p.stickB,false);stick(g,1028,150,-8,p.stickC,true);stick(g,1120,165,24,p.stickA,false);}
        else{stick(g,150,170,-20,p.stickA,true);stick(g,1090,170,20,p.stickB,false);}
    }

    // 리뷰 말풍선: 흰 풍선 + 초록 테두리 + 점 세 개. 별 모양은 별점 요구로 읽힐 수 있어 쓰지 않는다.
    private static void bubble(Graphics2D g,int cx,int cy,Palette p){
        Stroke old=g.getStroke();Polygon tail=new Polygon(new int[]{cx-46,cx-4,cx-66},new int[]{cy+56,cy+56,cy+96},3);
        g.setColor(new Color(22,36,54,30));g.fillRoundRect(cx-104,cy-64,220,132,64,64);
        g.setColor(PAPER);g.fillRoundRect(cx-110,cy-70,220,132,64,64);g.fillPolygon(tail);
        g.setColor(p.ring);g.setStroke(new BasicStroke(8,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));g.drawRoundRect(cx-110,cy-70,220,132,64,64);
        g.setColor(PAPER);g.fillRect(cx-44,cy+56,38,10);g.setColor(p.ring);g.drawPolyline(new int[]{cx-46,cx-66,cx-4},new int[]{cy+62,cy+96,cy+62},3);
        for(int i=-1;i<=1;i++)g.fillOval(cx+i*52-15,cy-19,30,30);g.setStroke(old);}

    // 윷가락: 둥근 막대 + 옅은 그림자. 표시가 있는 면(배)에는 전통 윷처럼 X 무늬를 새긴다.
    private static void stick(Graphics2D g,int x,int y,double angle,Color color,boolean marked){
        AffineTransform old=g.getTransform();Stroke oldStroke=g.getStroke();g.translate(x,y);g.rotate(Math.toRadians(angle));
        g.setColor(new Color(22,36,54,30));g.fillRoundRect(-24,-98,60,212,60,60);
        g.setColor(color);g.fillRoundRect(-30,-106,60,212,60,60);
        if(marked){g.setColor(NAVY);g.setStroke(new BasicStroke(7,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
            for(int dy=-50;dy<=50;dy+=50){g.drawLine(-9,dy-9,9,dy+9);g.drawLine(9,dy-9,-9,dy+9);}}
        g.setStroke(oldStroke);g.setTransform(old);}
    private static void drawQr(Graphics2D g,String value,int x,int y,int size){
        try{BitMatrix matrix=new QRCodeWriter().encode(value,BarcodeFormat.QR_CODE,size,size,Map.of(EncodeHintType.ERROR_CORRECTION,ErrorCorrectionLevel.H,EncodeHintType.MARGIN,4));g.setColor(PAPER);g.fillRect(x,y,size,size);g.setColor(Color.BLACK);for(int row=0;row<size;row++)for(int col=0;col<size;col++)if(matrix.get(col,row))g.fillRect(x+col,y+row,1,1);}
        catch(WriterException e){throw new IllegalStateException("QR 생성에 실패했습니다.",e);}
    }

    private static Graphics2D start(BufferedImage image){
        Graphics2D g=image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,RenderingHints.VALUE_FRACTIONALMETRICS_ON);return g;
    }
    private static byte[] png(BufferedImage image){
        try(ByteArrayOutputStream out=new ByteArrayOutputStream()){ImageIO.write(image,"png",out);return out.toByteArray();}
        catch(IOException e){throw new IllegalStateException("매장 QR 템플릿 생성에 실패했습니다.",e);}
    }

    private record Block(Font font,List<String> lines){}
    /** start부터 min까지 줄여 가며 maxLines 안에 들어가는 크기를 찾는다. min에서도 넘치면 마지막 줄을 말줄임한다. */
    private static Block block(Graphics2D g,String text,Font base,int start,int min,int maxW,int maxLines){
        for(int size=start;;size-=2){Font f=base.deriveFont((float)size);FontMetrics m=g.getFontMetrics(f);List<String> lines=wrap(m,text,maxW);
            if(lines.size()<=maxLines)return new Block(f,lines);
            if(size-2<min){List<String> cut=new ArrayList<>(lines.subList(0,maxLines));
                cut.set(maxLines-1,ellipsize(m,String.join(" ",lines.subList(maxLines-1,lines.size())),maxW));return new Block(f,cut);}}
    }
    /** 어절(공백) 단위 줄바꿈. 한 어절이 한 줄보다 길 때만 글자 단위로 자른다. */
    static List<String> wrap(FontMetrics m,String text,int maxW){
        List<String> lines=new ArrayList<>();StringBuilder line=new StringBuilder();
        for(String word:text.trim().split("\\s+")){
            String next=line.isEmpty()?word:line+" "+word;
            if(m.stringWidth(next)<=maxW){line.setLength(0);line.append(next);continue;}
            if(!line.isEmpty()){lines.add(line.toString());line.setLength(0);}
            for(char ch:word.toCharArray()){if(!line.isEmpty()&&m.stringWidth(line.toString()+ch)>maxW){lines.add(line.toString());line.setLength(0);}line.append(ch);}
        }
        if(!line.isEmpty())lines.add(line.toString());return lines;
    }
    private static String ellipsize(FontMetrics m,String text,int maxW){
        if(m.stringWidth(text)<=maxW)return text;String s=text;
        while(!s.isEmpty()&&m.stringWidth(s+"…")>maxW)s=s.substring(0,s.length()-1);return s.stripTrailing()+"…";
    }
    private static Font fit(Graphics2D g,String value,Font base,int start,int min,int maxWidth){
        IntFunction<Font> sized=size->base.deriveFont((float)size);int size=start;Font font;
        do{font=sized.apply(size--);}while(size>=min&&g.getFontMetrics(font).stringWidth(value)>maxWidth);return font;
    }
    private static void center(Graphics2D g,String value,int x,int baseline){g.drawString(value,x-g.getFontMetrics().stringWidth(value)/2,baseline);}
}
