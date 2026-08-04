#!/usr/bin/env python3
"""----------------------
 | authproxy.py
 | Description: Gates the multizork TCP port behind the Netlink shared-secret handshake so unauthenticated
 |              traffic never reaches the upstream C parser.
 | Author: suinevere
 | Dependencies: asyncio, hmac
 | Globals: N/A
 ----------------------"""
import asyncio
import hmac
import logging
import os
import sys

"""----------------------
 | load_config
 | Description: Reads proxy settings from the environment so no secret is ever baked into the image.
 | Author: suinevere
 | Dependencies: os
 | Globals: N/A
 | Params: N/A
 | Returns: dict of resolved settings
 ----------------------"""
def load_config():
    secret = os.environ.get("AUTH_SECRET", "")
    if not secret:
        sys.exit("AUTH_SECRET is required")

    return {
        "magic": os.environ.get("AUTH_MAGIC", "AUTH").encode(),
        "secret": secret.encode(),
        "listen_host": os.environ.get("LISTEN_HOST", "0.0.0.0"),
        "listen_port": int(os.environ.get("LISTEN_PORT", "2322")),
        "upstream_host": os.environ.get("UPSTREAM_HOST", "127.0.0.1"),
        "upstream_port": int(os.environ.get("UPSTREAM_PORT", "2323")),
        "auth_timeout": float(os.environ.get("AUTH_TIMEOUT", "5.0")),
        "max_conn": int(os.environ.get("MAX_CONN", "32")),
        "idle_timeout": float(os.environ.get("IDLE_TIMEOUT", "1800")),
    }

"""----------------------
 | verify_auth
 | Description: Reads the AUTH preamble and compares the secret in constant time.
 | Author: suinevere
 | Dependencies: asyncio, hmac
 | Globals: N/A
 | Params: reader -- client stream; config -- resolved settings
 | Returns: True when the preamble matches, False otherwise
 ----------------------"""
async def verify_auth(reader, config):
    magic = await reader.readexactly(len(config["magic"]))
    if not hmac.compare_digest(magic, config["magic"]):
        return False

    length = (await reader.readexactly(1))[0]
    if length == 0:
        return False

    offered = await reader.readexactly(length)
    return hmac.compare_digest(offered, config["secret"])

"""----------------------
 | pipe
 | Description: Copies one direction of the relay until either end closes.
 | Author: suinevere
 | Dependencies: asyncio
 | Globals: N/A
 | Params: reader -- source stream; writer -- destination stream
 | Returns: N/A
 ----------------------"""
async def pipe(reader, writer):
    try:
        while True:
            chunk = await reader.read(4096)
            if not chunk:
                break
            writer.write(chunk)
            await writer.drain()
    except (ConnectionError, asyncio.IncompleteReadError):
        pass
    finally:
        writer.close()

"""----------------------
 | handle_client
 | Description: Authenticates one inbound connection and relays it to the upstream service.
 | Author: suinevere
 | Dependencies: asyncio
 | Globals: N/A
 | Params: reader, writer -- client streams; config -- resolved settings; slots -- concurrency semaphore
 | Returns: N/A
 ----------------------"""
async def handle_client(reader, writer, config, slots):
    peer = writer.get_extra_info("peername")
    if slots.locked():
        logging.warning("rejected %s: at capacity", peer)
        writer.close()
        return

    async with slots:
        upstream_writer = None
        try:
            authorised = await asyncio.wait_for(
                verify_auth(reader, config), config["auth_timeout"]
            )
            if not authorised:
                logging.warning("rejected %s: bad preamble", peer)
                writer.close()
                return

            writer.write(b"\x01")
            await writer.drain()

            upstream_reader, upstream_writer = await asyncio.open_connection(
                config["upstream_host"], config["upstream_port"]
            )
            logging.info("relaying %s", peer)

            await asyncio.wait_for(
                asyncio.gather(
                    pipe(reader, upstream_writer),
                    pipe(upstream_reader, writer),
                ),
                config["idle_timeout"],
            )
        except asyncio.IncompleteReadError:
            logging.warning("rejected %s: truncated preamble", peer)
        except asyncio.TimeoutError:
            logging.warning("dropped %s: timed out", peer)
        except OSError as error:
            logging.warning("dropped %s: %s", peer, error)
        finally:
            writer.close()
            if upstream_writer is not None:
                upstream_writer.close()

"""----------------------
 | main
 | Description: Binds the listener and serves until terminated.
 | Author: suinevere
 | Dependencies: asyncio
 | Globals: N/A
 | Params: N/A
 | Returns: N/A
 ----------------------"""
async def main():
    logging.basicConfig(
        level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s"
    )
    config = load_config()
    slots = asyncio.Semaphore(config["max_conn"])

    server = await asyncio.start_server(
        lambda r, w: handle_client(r, w, config, slots),
        config["listen_host"],
        config["listen_port"],
    )
    logging.info(
        "listening on %s:%d, upstream %s:%d",
        config["listen_host"],
        config["listen_port"],
        config["upstream_host"],
        config["upstream_port"],
    )

    async with server:
        await server.serve_forever()

if __name__ == "__main__":
    asyncio.run(main())
