CREATE TABLE `commands` (
	`pair` text NOT NULL,
	`id` text NOT NULL,
	`issued` integer NOT NULL,
	`expires` integer NOT NULL,
	`envelope` text NOT NULL,
	`receipt` text,
	`rank` integer DEFAULT 0 NOT NULL,
	PRIMARY KEY(`pair`, `id`)
);
--> statement-breakpoint
CREATE TABLE `pairs` (
	`id` text PRIMARY KEY NOT NULL,
	`owner` text NOT NULL,
	`remote_hash` text NOT NULL,
	`gateway_hash` text NOT NULL,
	`created` integer NOT NULL,
	`revoked` integer DEFAULT 0 NOT NULL,
	`heartbeat` text,
	`last_seen` integer DEFAULT 0 NOT NULL
);
--> statement-breakpoint
CREATE UNIQUE INDEX `pairs_remote_hash_unique` ON `pairs` (`remote_hash`);--> statement-breakpoint
CREATE UNIQUE INDEX `pairs_gateway_hash_unique` ON `pairs` (`gateway_hash`);