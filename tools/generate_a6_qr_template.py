from __future__ import annotations

import argparse
from pathlib import Path

from reportlab.graphics import renderPDF
from reportlab.graphics.barcode import qr
from reportlab.graphics.shapes import Drawing
from reportlab.lib.colors import HexColor
from reportlab.lib.pagesizes import A6
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfgen.canvas import Canvas


BG = HexColor("#F4F7F5")
INK = HexColor("#16241F")
MUTED = HexColor("#4F5F58")
BRAND = HexColor("#B34A20")
BRAND_BRIGHT = HexColor("#D76535")
WOOD = HexColor("#8A5320")
WOOD_LIGHT = HexColor("#CFA273")
PAPER = HexColor("#FFFFFF")
LINE = HexColor("#DDE5E1")


def text(c: Canvas, value: str, x: float, y: float, font: str, size: float, color=INK) -> None:
    c.setFillColor(color)
    c.setFont(font, size)
    c.drawCentredString(x, y, value)


def make_pdf(output: Path, url: str, store_name: str) -> None:
    width, height = A6
    output.parent.mkdir(parents=True, exist_ok=True)
    pdfmetrics.registerFont(TTFont("Malgun", r"C:\Windows\Fonts\malgun.ttf"))
    pdfmetrics.registerFont(TTFont("MalgunBold", r"C:\Windows\Fonts\malgunbd.ttf"))

    c = Canvas(str(output), pagesize=A6, pageCompression=1)
    c.setTitle(f"{store_name} A6 QR 안내 템플릿")
    c.setAuthor("yut-review")
    c.setFillColor(INK)
    c.rect(0, 0, width, height, fill=1, stroke=0)

    # 결과가 터져 나오는 순간처럼 네 개의 윷가락을 한 덩어리로 던진다.
    sticks = [
        (65, 118, -24, WOOD_LIGHT, True),
        (76, 122, -8, BRAND_BRIGHT, False),
        (87, 116, 13, WOOD, True),
        (95, 125, 26, WOOD_LIGHT, False),
    ]
    for x_mm, y_mm, angle, color, marked in sticks:
        c.saveState()
        c.translate(x_mm * mm, y_mm * mm)
        c.rotate(angle)
        c.setFillColor(color)
        c.roundRect(-3.2 * mm, -13 * mm, 6.4 * mm, 26 * mm, 3.2 * mm, fill=1, stroke=0)
        if marked:
            c.setFillColor(INK)
            c.circle(0, 0, 0.8 * mm, fill=1, stroke=0)
        c.restoreState()

    c.setFillColor(BRAND_BRIGHT)
    c.roundRect(10 * mm, height - 17 * mm, 7 * mm, 2.2 * mm, 1.1 * mm, fill=1, stroke=0)
    c.setFillColor(PAPER)
    c.setFont("MalgunBold", 8)
    c.drawString(20 * mm, height - 17.2 * mm, store_name)

    c.setFillColor(PAPER)
    c.setFont("MalgunBold", 21)
    c.drawString(10 * mm, height - 35 * mm, "리뷰 후")
    c.setFillColor(BRAND_BRIGHT)
    c.setFont("MalgunBold", 32)
    c.drawString(9.5 * mm, height - 48 * mm, "윷 한 판!")
    c.setFillColor(HexColor("#C7D2CD"))
    c.setFont("Malgun", 8.5)
    c.drawString(10 * mm, height - 56 * mm, "리뷰를 작성하고 윷을 던져 보세요")

    # QR은 장식보다 앞으로 나온 단일 행동 면이다.
    qr_size = 47 * mm
    plate_w, plate_h = 61 * mm, 68 * mm
    plate_x = (width - plate_w) / 2
    plate_y = 20 * mm
    c.setFillColor(PAPER)
    c.roundRect(plate_x, plate_y, plate_w, plate_h, 5 * mm, fill=1, stroke=0)

    text(c, "참여하려면", width / 2, plate_y + plate_h - 9 * mm, "Malgun", 7.5, MUTED)
    text(c, "QR을 스캔하세요", width / 2, plate_y + plate_h - 16 * mm, "MalgunBold", 12.5)

    widget = qr.QrCodeWidget(url, barLevel="H")
    bounds = widget.getBounds()
    scale = qr_size / max(bounds[2] - bounds[0], bounds[3] - bounds[1])
    drawing = Drawing(qr_size, qr_size, transform=[scale, 0, 0, scale, 0, 0])
    drawing.add(widget)
    renderPDF.draw(drawing, c, plate_x + 7 * mm, plate_y + 4.5 * mm)

    c.setFillColor(BRAND)
    c.rect(0, 0, width, 15 * mm, fill=1, stroke=0)
    text(c, "앱 설치 없이 바로 참여", width / 2, 8.7 * mm, "MalgunBold", 9, PAPER)
    text(c, "샘플 QR · 출력 전 매장 URL로 교체", width / 2, 3.5 * mm, "Malgun", 7.2, HexColor("#F4D8CB"))
    c.showPage()
    c.save()


def main() -> None:
    parser = argparse.ArgumentParser(description="A6 매장용 QR 안내 PDF 생성")
    parser.add_argument("--url", default="https://example.invalid/s/REPLACE_WITH_STORE_TOKEN")
    parser.add_argument("--store-name", default="우리 매장 리뷰 이벤트")
    parser.add_argument("--output", type=Path, default=Path("output/pdf/매장용_A6_QR_템플릿.pdf"))
    args = parser.parse_args()
    make_pdf(args.output, args.url, args.store_name)


if __name__ == "__main__":
    main()
