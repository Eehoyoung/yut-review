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
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Service;

/** 안내물 종류. GAME이 기본이며 저장되는 것도 GAME뿐이다. 나머지는 요청할 때 그린다. */
enum PosterVariant { GAME, EVENT, REVISIT }

@Service class StorePosterService {
    static final int WIDTH=1240,HEIGHT=1748;
    // DESIGN.md 팔레트(소담랩스 로고 색). 인쇄물은 밝은 바탕 위에 네이비 글자와 오렌지 강조를 쓴다.
    private static final Color NAVY=new Color(0x162436),PAPER=Color.WHITE,ORANGE=new Color(0xFC672D),ORANGE_DEEP=new Color(0xD9531C),
        CREAM=new Color(0xFCDAC2),YELLOW=new Color(0xFFE27A),WOOD=new Color(0x9A5B2A),WOOD_LIGHT=new Color(0xD9A873);
    // 운영 이미지(fonts-nanum)에는 둥근 고딕이 있고, 개발 PC(Windows)에는 맑은 고딕이 있다.
    private static final String FAMILY=Arrays.stream(new String[]{"NanumSquareRound","NanumSquare","NanumGothic","Malgun Gothic"})
        .filter(Set.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames())::contains).findFirst().orElse(Font.SANS_SERIF);
    // 제목·상호·단계처럼 큰 글자는 주아체(OFL, resources/fonts)로 귀엽게, 작은 안내문은 위 고딕으로 읽기 쉽게 둔다.
    private static final Font CUTE=loadCute();
    private static Font loadCute(){
        try(var in=StorePosterService.class.getResourceAsStream("/fonts/Jua-Regular.ttf")){return Font.createFont(Font.TRUETYPE_FONT,in);}
        catch(Exception e){return new Font(FAMILY,Font.BOLD,12);}
    }
    private final StorePosterRepository posters;private final Clock clock;
    StorePosterService(StorePosterRepository posters,Clock clock){this.posters=posters;this.clock=clock;}

    StorePoster save(Store store,String storeToken,String publicOrigin){
        return save(store,storeToken,publicOrigin,null);
    }

    /**
     * 안내물 저장. tagline은 STANDARD 이상에서만 채워져 들어온다(브랜딩 권한).
     * 권한 판단은 호출부가 하고 여기서는 받은 값을 그릴 뿐이다.
     */
    StorePoster save(Store store,String storeToken,String publicOrigin,String tagline){
        String origin=origin(publicOrigin),url=origin+"/s/"+storeToken;
        StorePoster poster=posters.findByStoreId(store.id).orElseGet(StorePoster::new);Instant now=clock.instant();
        if(poster.id==null){poster.store=store;poster.createdAt=now;}
        poster.contentBase64=Base64.getEncoder().encodeToString(render(store.name,url,tagline));poster.publicOrigin=origin;poster.updatedAt=now;
        return posters.save(poster);
    }

    byte[] bytes(StorePoster poster){return Base64.getDecoder().decode(poster.contentBase64);}

    private static String origin(String value){
        try{URI uri=URI.create(value);if((!"http".equals(uri.getScheme())&&!"https".equals(uri.getScheme()))||uri.getHost()==null||uri.getUserInfo()!=null)throw new IllegalArgumentException();return uri.getScheme()+"://"+uri.getRawAuthority();}
        catch(IllegalArgumentException e){throw new AppException("INVALID_PUBLIC_ORIGIN","공개 접속 주소가 올바르지 않습니다.");}
    }

    static byte[] render(String storeName,String url){return render(storeName,url,null);}
    static byte[] render(String storeName,String url,String tagline){return render(PosterVariant.GAME,storeName,url,tagline);}

    /**
     * 세 안내물은 같은 뼈대(상호 → 제목 → QR 판 → 참여 3단계 → 하단 띠)에 색·장식·문구만 다르다.
     * QR 판의 크기(47mm)와 위치가 셋 다 같아서 어느 것을 붙여도 스캔 거리가 같다.
     * tagline은 매장이 직접 쓴 한 줄이며 STANDARD 이상에서만 채워진다(브랜딩 권한). 없으면 종류별 기본 문구를 쓴다.
     * 리뷰·별점은 참여 조건이 아니므로 어느 안내물에도 적지 않는다.
     */
    static byte[] render(PosterVariant variant,String storeName,String url,String tagline){
        Theme t=Theme.of(variant);
        BufferedImage image=new BufferedImage(WIDTH,HEIGHT,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setPaint(new GradientPaint(0,0,t.bgTop,0,HEIGHT,t.bgBottom));g.fillRect(0,0,WIDTH,HEIGHT);
        decorate(g,variant);

        g.setFont(fit(g,storeName,CUTE_SIZE,48,600));FontMetrics m=g.getFontMetrics();int pillW=m.stringWidth(storeName)+122;
        g.setColor(t.pill);g.fillRoundRect(96,96,pillW,96,96,96);
        g.setColor(t.dot);g.fillOval(130,132,24,24);
        g.setColor(t.pillInk);g.drawString(storeName,176,144+(m.getAscent()-m.getDescent())/2);

        g.setColor(t.ink);g.setFont(fit(g,t.line1,CUTE_SIZE,96,1040));g.drawString(t.line1,96,392);
        g.setColor(t.accent);g.setFont(fit(g,t.line2,CUTE_SIZE,132,1048));g.drawString(t.line2,90,540);
        String sub=tagline==null||tagline.isBlank()?t.sub:tagline.trim();
        g.setColor(t.subInk);g.setFont(fit(g,sub,size->font(Font.PLAIN,size),40,1040));g.drawString(sub,98,622);

        int plateX=230,plateY=690,plateW=780,plateH=720;
        g.setColor(t.plateShadow);g.fillRoundRect(plateX+18,plateY+22,plateW,plateH,64,64);
        g.setColor(PAPER);g.fillRoundRect(plateX,plateY,plateW,plateH,64,64);
        g.setColor(NAVY);g.setFont(cute(44));center(g,"휴대폰 카메라로 비춰 주세요",WIDTH/2,772);
        drawQr(g,url,342,812,555);

        String[] steps={"QR 비추기","윷 던지기","쿠폰 받기"};
        for(int i=0;i<steps.length;i++){int x=96+i*360;
            g.setColor(t.stepDot);g.fillOval(x,1464,72,72);
            g.setColor(t.stepDotInk);g.setFont(cute(42));center(g,Integer.toString(i+1),x+36,1514);
            g.setColor(t.ink);g.drawString(steps[i],x+90,1514);}

        g.setColor(t.band);g.fillRect(0,1598,WIDTH,HEIGHT-1598);
        g.setColor(PAPER);g.setFont(cute(44));center(g,"앱 설치 없이 이름과 번호만 입력하면 돼요",WIDTH/2,1672);
        g.setColor(t.bandSub);g.setFont(font(Font.PLAIN,28));center(g,t.footnote,WIDTH/2,1718);
        g.dispose();
        try(ByteArrayOutputStream out=new ByteArrayOutputStream()){ImageIO.write(image,"png",out);return out.toByteArray();}
        catch(IOException e){throw new IllegalStateException("매장 QR 템플릿 생성에 실패했습니다.",e);}
    }

    private record Theme(Color bgTop,Color bgBottom,Color ink,Color accent,Color subInk,Color pill,Color pillInk,Color dot,Color plateShadow,
                         Color stepDot,Color stepDotInk,Color band,Color bandSub,String line1,String line2,String sub,String footnote){
        static Theme of(PosterVariant v){return switch(v){
            case GAME->new Theme(new Color(0xFFF8F0),new Color(0xFFE4CE),NAVY,ORANGE_DEEP,new Color(0x4C5A6B),NAVY,PAPER,ORANGE,ORANGE,
                ORANGE_DEEP,PAPER,NAVY,CREAM,"윷 한 판 던져요~","상품이 기다려요","QR을 찍으면 바로 시작해요","도·개·걸·윷·모, 무엇이 나와도 쿠폰을 드려요");
            case EVENT->new Theme(new Color(0xFF8C4E),new Color(0xF2541A),PAPER,YELLOW,new Color(0xFFF1E6),PAPER,NAVY,ORANGE,NAVY,
                PAPER,ORANGE_DEEP,NAVY,CREAM,"만나서 반가워요","깜짝 선물 기대하세요!","윷 한 번 던지고 선물 받아 가세요","쿠폰 쓰는 방법은 결과 화면에서 알려 드려요");
            case REVISIT->new Theme(new Color(0xF2F9F5),new Color(0xD3EBDF),NAVY,ORANGE_DEEP,new Color(0x3F5A52),PAPER,NAVY,new Color(0x2E8B68),new Color(0xA5D6C0),
                new Color(0x2E8B68),PAPER,new Color(0x1F5E4A),new Color(0xCFEBDD),"오늘 좋은 경험을 다음에도 경험해 보세요!","감사의 선물 받아 가세요","오늘 던지셨다면 모레 또 만나요","아직 쓰지 않은 쿠폰이 있으면 그 쿠폰부터 보여 드려요");
        };}
    }

    private static void decorate(Graphics2D g,PosterVariant v){
        switch(v){
            case GAME->{
                g.setColor(new Color(0xFFE2CB));g.fillOval(860,-140,460,460);
                stick(g,880,160,-28,WOOD_LIGHT,true);stick(g,985,132,-9,ORANGE,false);stick(g,1085,158,12,WOOD,true);stick(g,1180,122,30,WOOD_LIGHT,false);
                g.setColor(new Color(0xFFD5B5));g.fillOval(96,1236,96,96);g.fillOval(1084,868,112,112);}
            case EVENT->{
                // 좌표를 고정해 두어 같은 매장은 늘 같은 그림이 나온다. 글자와 QR 판을 피해 가장자리에만 뿌린다.
                int[][] bits={{800,70,0},{900,160,1},{1010,64,2},{1110,176,0},{1190,70,3},{1190,300,1},
                    {70,730,1},{160,870,3},{80,1020,0},{170,1170,2},{70,1330,3},{1080,740,2},{1180,900,0},{1100,1060,3},{1190,1210,1},{1090,1350,0}};
                Color[] colors={PAPER,YELLOW,NAVY,CREAM};
                for(int[] b:bits){AffineTransform old=g.getTransform();g.translate(b[0],b[1]);g.rotate(Math.toRadians(b[0]*7%90-45));g.setColor(colors[b[2]]);
                    if(b[2]%2==0)g.fillRoundRect(-24,-10,48,20,10,10);else g.fillOval(-15,-15,30,30);g.setTransform(old);}
                stick(g,1000,190,-20,YELLOW,false);stick(g,1105,200,16,PAPER,true);}
            case REVISIT->{
                g.setColor(new Color(0xDFF1E8));g.fillOval(820,-160,520,520);g.setColor(new Color(0xC9E7D8));g.fillOval(990,20,320,320);
                stick(g,1010,190,-32,WOOD_LIGHT,true);stick(g,1110,180,32,ORANGE,false);
                g.setColor(new Color(0xC9E7D8));g.fillOval(96,1236,96,96);g.fillOval(1084,868,112,112);}
        }
    }

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
    private static Font font(int style,int size){return new Font(FAMILY,style,size);}
    private static Font cute(int size){return CUTE.deriveFont((float)size);}
    private static final java.util.function.IntFunction<Font> CUTE_SIZE=StorePosterService::cute;
    private static Font fit(Graphics2D g,String value,java.util.function.IntFunction<Font> sized,int start,int maxWidth){int size=start;Font font;do{font=sized.apply(size--);}while(size>18&&g.getFontMetrics(font).stringWidth(value)>maxWidth);return font;}
    private static void center(Graphics2D g,String value,int x,int baseline){g.drawString(value,x-g.getFontMetrics().stringWidth(value)/2,baseline);}
}
