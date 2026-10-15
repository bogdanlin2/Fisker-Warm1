import type { Metadata } from "next";
import "./globals.css";
export const metadata: Metadata = {title:"Fisker Warm · Test Relay",description:"Приватний сервер тестової інтернет-доставки. HVAC заблокований."};
export default function RootLayout({children}:Readonly<{children:React.ReactNode}>){return <html lang="uk"><body>{children}</body></html>;}
