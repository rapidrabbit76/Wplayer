#!/usr/bin/env python3
"""An FTP server like the older ones Wplayer meets, for scripts/test-servers.sh.

    ftp-test-server.py PORT DIR [--no-mlsd] [--encoding cp949]

--no-mlsd rejects MLSD and MLST as unknown commands, as vsftpd does.
--encoding sends and reads file names in that encoding, as Korean Windows
servers and older NAS do. Needs pyftpdlib; signs in tester/secret.
"""

import argparse

from pyftpdlib.authorizers import DummyAuthorizer
from pyftpdlib.handlers import FTPHandler
from pyftpdlib.servers import FTPServer

parser = argparse.ArgumentParser()
parser.add_argument("port", type=int)
parser.add_argument("directory")
parser.add_argument("--no-mlsd", action="store_true")
parser.add_argument("--encoding", default="utf8")
args = parser.parse_args()


class Handler(FTPHandler):
    encoding = args.encoding
    proto_cmds = {
        name: spec
        for name, spec in FTPHandler.proto_cmds.items()
        if not (args.no_mlsd and name in ("MLSD", "MLST"))
    }


authorizer = DummyAuthorizer()
authorizer.add_user("tester", "secret", args.directory)
Handler.authorizer = authorizer
FTPServer(("127.0.0.1", args.port), Handler).serve_forever()
