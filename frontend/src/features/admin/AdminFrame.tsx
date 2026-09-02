"use client";
import Link from "next/link";
import { useParams } from "next/navigation";
const menus=[["dashboard","대시보드"],["prizes","상품 설정"],["qr","QR 관리"],["staff-pin","직원 PIN"],["game-plays","참여 내역"],["coupons","쿠폰 내역"],["analytics","통계"]];
export function AdminFrame({title,children}:{title:string;children:React.ReactNode}){const id=String(useParams().storeId);return <main className="admin-shell"><p className="brand">STORE CONSOLE</p><div className="row"><h1>{title}</h1><Link href="/admin">매장 변경</Link></div><nav className="nav">{menus.map(([path,label])=><Link key={path} href={`/admin/stores/${id}/${path}`}>{label}</Link>)}</nav>{children}</main>}

