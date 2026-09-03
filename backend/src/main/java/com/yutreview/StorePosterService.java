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
import java.util.Base64;
import java.util.Map;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Service;

@Service class StorePosterService {
    static final int WIDTH=1240,HEIGHT=1748;
    private static final Color INK=new Color(0x16241F),PAPER=Color.WHITE,TERRACOTTA=new Color(0xB34A20),
        TERRACOTTA_BRIGHT=new Color(0xD76535),WOOD=new Color(0x8A5320),WOOD_LIGHT=new Color(0xCFA273),MUTED=new Color(0xC7D2CD);
    private final StorePosterRepository posters;private final Clock clock;
    StorePosterService(StorePosterRepository posters,Clock clock){this.posters=posters;this.clock=clock;}

    StorePoster save(Store store,String storeToken,String publicOrigin){
        String origin=origin(publicOrigin),url=origin+"/s/"+storeToken;
        StorePoster poster=posters.findByStoreId(store.id).orElseGet(StorePoster::new);Instant now=clock.instant();
        if(poster.id==null){poster.store=store;poster.createdAt=now;}
        poster.contentBase64=Base64.getEncoder().encodeToString(render(store.name,url));poster.publicOrigin=origin;poster.updatedAt=now;
        return posters.save(poster);
    }

    byte[] bytes(StorePoster poster){return Base64.getDecoder().decode(poster.contentBase64);}

    private static String origin(String value){
        try{URI uri=URI.create(value);if((!"http".equals(uri.getScheme())&&!"https".equals(uri.getScheme()))||uri.getHost()==null||uri.getUserInfo()!=null)throw new IllegalArgumentException();return uri.getScheme()+"://"+uri.getRawAuthority();}
        catch(IllegalArgumentException e){throw new AppException("INVALID_PUBLIC_ORIGIN","공개 접속 주소가 올바르지 않습니다.");}
    }

    static byte[] render(String storeName,String url){
        BufferedImage image=new BufferedImage(WIDTH,HEIGHT,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(INK);g.fillRect(0,0,WIDTH,HEIGHT);
        g.setColor(TERRACOTTA_BRIGHT);g.fillRoundRect(118,111,70,22,22,22);
        g.setColor(PAPER);g.setFont(fit(g,storeName,Font.BOLD,34,900));g.drawString(storeName,220,137);
        g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,86));g.drawString("리뷰 후",118,382);
        g.setColor(TERRACOTTA_BRIGHT);g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,132));g.drawString("윷 한 판!",112,530);
        g.setColor(MUTED);g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,35));g.drawString("솔직한 리뷰를 남기고 행운을 던져보세요",118,625);
        stick(g,805,305,-24,WOOD_LIGHT,true);stick(g,930,300,-8,TERRACOTTA_BRIGHT,false);stick(g,1040,325,13,WOOD,true);stick(g,1145,250,26,WOOD_LIGHT,false);

        int plateX=250,plateY=675,plateW=740,plateH=805;g.setColor(PAPER);g.fillRoundRect(plateX,plateY,plateW,plateH,70,70);
        g.setColor(new Color(0x4F5F58));g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,31));center(g,"카메라를 켜고",WIDTH/2,770);
        g.setColor(INK);g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,52));center(g,"QR을 스캔하세요",WIDTH/2,842);
        drawQr(g,url,343,904,555);

        g.setColor(TERRACOTTA);g.fillRect(0,1570,WIDTH,178);g.setColor(PAPER);g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,38));center(g,"앱 설치 없이 바로 참여",WIDTH/2,1650);
        g.setColor(new Color(0xF4D8CB));g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,24));center(g,"매장 전용 QR · 휴대폰에 저장하거나 바로 공유하세요",WIDTH/2,1708);
        g.dispose();
        try(ByteArrayOutputStream out=new ByteArrayOutputStream()){ImageIO.write(image,"png",out);return out.toByteArray();}
        catch(IOException e){throw new IllegalStateException("매장 QR 템플릿 생성에 실패했습니다.",e);}
    }

    private static void stick(Graphics2D g,int x,int y,double angle,Color color,boolean marked){AffineTransform old=g.getTransform();g.translate(x,y);g.rotate(Math.toRadians(angle));g.setColor(color);g.fillRoundRect(-34,-130,68,260,68,68);if(marked){g.setColor(INK);g.fillOval(-9,-9,18,18);}g.setTransform(old);}
    private static void drawQr(Graphics2D g,String value,int x,int y,int size){
        try{BitMatrix matrix=new QRCodeWriter().encode(value,BarcodeFormat.QR_CODE,size,size,Map.of(EncodeHintType.ERROR_CORRECTION,ErrorCorrectionLevel.H,EncodeHintType.MARGIN,4));g.setColor(PAPER);g.fillRect(x,y,size,size);g.setColor(Color.BLACK);for(int row=0;row<size;row++)for(int col=0;col<size;col++)if(matrix.get(col,row))g.fillRect(x+col,y+row,1,1);}
        catch(WriterException e){throw new IllegalStateException("QR 생성에 실패했습니다.",e);}
    }
    private static Font fit(Graphics2D g,String value,int style,int start,int maxWidth){int size=start;Font font;do{font=new Font(Font.SANS_SERIF,style,size--);}while(size>18&&g.getFontMetrics(font).stringWidth(value)>maxWidth);return font;}
    private static void center(Graphics2D g,String value,int x,int baseline){g.drawString(value,x-g.getFontMetrics().stringWidth(value)/2,baseline);}
}
