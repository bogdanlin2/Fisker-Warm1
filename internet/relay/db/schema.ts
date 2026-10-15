import { sqliteTable, text, integer, primaryKey } from "drizzle-orm/sqlite-core";
export const pairs = sqliteTable("pairs", {
 id: text("id").primaryKey(), owner: text("owner").notNull(), remoteHash: text("remote_hash").notNull().unique(), gatewayHash: text("gateway_hash").notNull().unique(),
 created: integer("created").notNull(), revoked: integer("revoked").notNull().default(0), heartbeat: text("heartbeat"), lastSeen: integer("last_seen").notNull().default(0)
});
export const commands = sqliteTable("commands", {
 pair: text("pair").notNull(), id: text("id").notNull(), issued: integer("issued").notNull(), expires: integer("expires").notNull(), envelope: text("envelope").notNull(),
 receipt: text("receipt"), rank: integer("rank").notNull().default(0)
}, t => [primaryKey({columns:[t.pair,t.id]})]);
