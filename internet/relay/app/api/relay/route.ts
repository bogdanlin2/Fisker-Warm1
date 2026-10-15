import { env } from "cloudflare:workers";
import { handle } from "../../../lib/relay.mjs";
export const dynamic = "force-dynamic";
export async function POST(request: Request) { return handle(request, env); }
