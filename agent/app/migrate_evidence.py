import argparse
import asyncio
import json
import sqlite3
import struct
from contextlib import closing
from pathlib import Path

import httpx

from app.evidence import EMBEDDING_DIMENSIONS, _unit_vector
from app.qdrant_evidence import (COLLECTION, RUN_COLLECTION, POINT_BATCH_SIZE,
                                 QdrantEvidenceIndex, _day, _point_id, configured_index)


async def migrate(path: str, index: QdrantEvidenceIndex) -> dict[str, int]:
    source = Path(path)
    if not source.is_file():
        raise FileNotFoundError(f"Legacy evidence database not found: {source}")
    await index.ensure_collections()
    counts = {"readChunks": 0, "importedChunks": 0, "readRuns": 0, "importedRuns": 0}
    with closing(sqlite3.connect(source.resolve().as_uri() + "?mode=ro", uri=True)) as database:
        rows = database.execute("""
            SELECT chunk_id, project_id, ref_name, source_type, source_id, entity_id,
                   author, created_at, updated_at, event_date, url, labels,
                   content, content_hash, embedding_model, embedding FROM evidence
        """)
        while batch := rows.fetchmany(POINT_BATCH_SIZE):
            counts["readChunks"] += len(batch)
            ids = [_point_id("chunk", row[0]) for row in batch]
            existing = await index._get_points(COLLECTION, ids)
            points = []
            for row, point_id in zip(batch, ids):
                if point_id in existing:
                    continue
                vector = _unit_vector(list(struct.unpack(
                    f"<{EMBEDDING_DIMENSIONS}f", row[15])))
                points.append({"id": point_id, "vector": vector, "payload": {
                    "chunk_id": row[0], "project_id": row[1], "ref_name": row[2],
                    "source_type": row[3], "source_id": row[4], "entity_id": row[5],
                    "author": row[6], "created_at": row[7], "updated_at": row[8],
                    "event_date": row[9], "event_day": _day(row[9]), "url": row[10],
                    "labels": json.loads(row[11]), "content": row[12],
                    "content_hash": row[13], "embedding_model": row[14],
                }})
            await index._upsert(COLLECTION, points)
            counts["importedChunks"] += len(points)

        runs = database.execute("""
            SELECT project_id, since, until, ref_name, refreshed_at, truncated FROM index_runs
        """)
        while batch := runs.fetchmany(POINT_BATCH_SIZE):
            counts["readRuns"] += len(batch)
            ids = [_point_id("run", f"{row[0]}:{row[3]}:{row[1]}:{row[2]}") for row in batch]
            existing = await index._get_points(RUN_COLLECTION, ids)
            points = []
            for row, point_id in zip(batch, ids):
                if point_id in existing:
                    continue
                points.append({"id": point_id, "vector": [1.0], "payload": {
                    "project_id": row[0], "since": row[1], "until": row[2],
                    "ref_name": row[3], "since_day": _day(row[1]),
                    "until_day": _day(row[2]), "refreshed_at": row[4],
                    "truncated": bool(row[5]),
                }})
            await index._upsert(RUN_COLLECTION, points)
            counts["importedRuns"] += len(points)
    return counts


async def main() -> None:
    parser = argparse.ArgumentParser(description="Import the legacy SQLite RAG index into Qdrant")
    parser.add_argument("--source", default="/legacy/evidence.sqlite")
    args = parser.parse_args()
    async with httpx.AsyncClient() as client:
        result = await migrate(args.source, configured_index(client))
    print(json.dumps(result))


if __name__ == "__main__":
    asyncio.run(main())
