"""Tasks (once or repeating) and the plant's own documentation."""

import uuid
from datetime import UTC, datetime

from fastapi import APIRouter, Query, Response, status
from sqlalchemy import or_, select

from ..models import Device, Document, Task
from ..schemas import (
    DocumentBrief,
    DocumentCreate,
    DocumentOut,
    DocumentUpdate,
    TaskCompleteOut,
    TaskCreate,
    TaskOut,
    TaskUpdate,
)
from ..services.repeat import next_due
from .deps import SessionDep, not_found

router = APIRouter(tags=["work"])

_EXCERPT = 160


def _today():
    return datetime.now(UTC).date()


async def _names(session, ids) -> dict[uuid.UUID, str]:
    ids = {i for i in ids if i}
    if not ids:
        return {}
    rows = await session.execute(select(Device.id, Device.name).where(Device.id.in_(ids)))
    return dict(rows.all())


def _task_out(t: Task, names: dict[uuid.UUID, str]) -> TaskOut:
    return TaskOut(
        id=t.id,
        title=t.title,
        notes=t.notes,
        due_date=t.due_date,
        repeat=t.repeat,
        repeat_every=t.repeat_every,
        device_id=t.device_id,
        device_name=names.get(t.device_id) if t.device_id else None,
        is_done=t.is_done,
        done_at=t.done_at,
        days_left=(t.due_date - _today()).days if t.due_date else None,
    )


async def _check_device(session, device_id: uuid.UUID | None) -> None:
    if device_id and await session.get(Device, device_id) is None:
        raise not_found("device")


# --- tasks ----------------------------------------------------------------------------------------------------------


@router.get("/tasks", response_model=list[TaskOut])
async def list_tasks(
    session: SessionDep,
    include_done: bool = False,
    device_id: uuid.UUID | None = None,
    limit: int = Query(default=500, ge=1, le=2000),
):
    """Open tasks, overdue first, then by date, those without a date last. `include_done` adds the finished ones
    (newest first, after the open ones)."""
    stmt = select(Task)
    if device_id:
        stmt = stmt.where(Task.device_id == device_id)
    rows = (await session.scalars(stmt)).all()
    open_ = sorted((t for t in rows if not t.is_done), key=lambda t: (t.due_date is None, t.due_date, t.title))
    done = sorted(
        (t for t in rows if t.is_done), key=lambda t: t.done_at or datetime.min.replace(tzinfo=UTC), reverse=True
    )
    picked = (open_ + (done[:100] if include_done else []))[:limit]
    names = await _names(session, (t.device_id for t in picked))
    return [_task_out(t, names) for t in picked]


@router.get("/tasks/summary")
async def tasks_summary(session: SessionDep):
    """Counts for the start screen and the menu badge."""
    today = _today()
    open_ = (await session.scalars(select(Task).where(Task.is_done.is_(False)))).all()
    dated = [t for t in open_ if t.due_date]
    return {
        "open": len(open_),
        "overdue": sum(1 for t in dated if t.due_date < today),
        "today": sum(1 for t in dated if t.due_date == today),
        "week": sum(1 for t in dated if 0 < (t.due_date - today).days <= 7),
    }


@router.post("/tasks", response_model=TaskOut, status_code=status.HTTP_201_CREATED)
async def create_task(body: TaskCreate, session: SessionDep):
    await _check_device(session, body.device_id)
    t = Task(**body.model_dump())
    # A repeat needs a date to count from: today when none was given.
    if t.repeat != "none" and t.due_date is None:
        t.due_date = _today()
    session.add(t)
    await session.commit()
    await session.refresh(t)
    return _task_out(t, await _names(session, [t.device_id]))


@router.patch("/tasks/{task_id}", response_model=TaskOut)
async def update_task(task_id: uuid.UUID, body: TaskUpdate, session: SessionDep):
    t = await session.get(Task, task_id)
    if t is None:
        raise not_found("task")
    data = body.model_dump(exclude_unset=True)
    if "device_id" in data:
        await _check_device(session, data["device_id"])
    for k, v in data.items():
        setattr(t, k, v)
    if t.repeat != "none" and t.due_date is None:
        t.due_date = _today()
    await session.commit()
    await session.refresh(t)
    return _task_out(t, await _names(session, [t.device_id]))


@router.post("/tasks/{task_id}/complete", response_model=TaskCompleteOut)
async def complete_task(task_id: uuid.UUID, session: SessionDep):
    """Marks it done. A repeating task gets its next occurrence: counted from the date it was due, but never in the
    past (a quarterly check done a month late is next due in two months, not two weeks ago)."""
    t = await session.get(Task, task_id)
    if t is None:
        raise not_found("task")
    if t.is_done:
        return TaskCompleteOut(done=_task_out(t, await _names(session, [t.device_id])))
    t.is_done = True
    t.done_at = datetime.now(UTC)
    nxt = None
    if t.repeat != "none":
        base = t.due_date or _today()
        due = next_due(base, t.repeat, t.repeat_every)
        while due is not None and due < _today():
            due = next_due(due, t.repeat, t.repeat_every)
        if due is not None:
            nxt = Task(
                title=t.title,
                notes=t.notes,
                due_date=due,
                repeat=t.repeat,
                repeat_every=t.repeat_every,
                device_id=t.device_id,
            )
            session.add(nxt)
    await session.commit()
    await session.refresh(t)
    names = await _names(session, [t.device_id])
    if nxt is not None:
        await session.refresh(nxt)
    return TaskCompleteOut(done=_task_out(t, names), next=_task_out(nxt, names) if nxt else None)


@router.post("/tasks/{task_id}/reopen", response_model=TaskOut)
async def reopen_task(task_id: uuid.UUID, session: SessionDep):
    t = await session.get(Task, task_id)
    if t is None:
        raise not_found("task")
    t.is_done = False
    t.done_at = None
    await session.commit()
    await session.refresh(t)
    return _task_out(t, await _names(session, [t.device_id]))


@router.delete("/tasks/{task_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_task(task_id: uuid.UUID, session: SessionDep):
    t = await session.get(Task, task_id)
    if t is None:
        raise not_found("task")
    await session.delete(t)
    await session.commit()
    return Response(status_code=status.HTTP_204_NO_CONTENT)


# --- documents --------------------------------------------------------------------------------------------------------


def _doc_out(d: Document, names: dict[uuid.UUID, str]) -> DocumentOut:
    return DocumentOut(
        id=d.id,
        title=d.title,
        category=d.category,
        body=d.body,
        device_id=d.device_id,
        device_name=names.get(d.device_id) if d.device_id else None,
        pinned=d.pinned,
        updated_at=d.updated_at,
    )


@router.get("/documents", response_model=list[DocumentBrief])
async def list_documents(
    session: SessionDep,
    q: str | None = Query(default=None, max_length=100, description="Substring of the title or the text"),
    category: str | None = None,
    device_id: uuid.UUID | None = None,
):
    stmt = select(Document)
    if q:
        like = f"%{q}%"
        stmt = stmt.where(or_(Document.title.ilike(like), Document.body.ilike(like)))
    if category:
        stmt = stmt.where(Document.category == category)
    if device_id:
        stmt = stmt.where(Document.device_id == device_id)
    rows = (await session.scalars(stmt.order_by(Document.pinned.desc(), Document.title))).all()
    names = await _names(session, (d.device_id for d in rows))
    return [
        DocumentBrief(
            id=d.id,
            title=d.title,
            category=d.category,
            excerpt=" ".join(d.body.split())[:_EXCERPT],
            device_id=d.device_id,
            device_name=names.get(d.device_id) if d.device_id else None,
            pinned=d.pinned,
            updated_at=d.updated_at,
        )
        for d in rows
    ]


@router.post("/documents", response_model=DocumentOut, status_code=status.HTTP_201_CREATED)
async def create_document(body: DocumentCreate, session: SessionDep):
    await _check_device(session, body.device_id)
    d = Document(**body.model_dump())
    session.add(d)
    await session.commit()
    await session.refresh(d)
    return _doc_out(d, await _names(session, [d.device_id]))


@router.get("/documents/{doc_id}", response_model=DocumentOut)
async def get_document(doc_id: uuid.UUID, session: SessionDep):
    d = await session.get(Document, doc_id)
    if d is None:
        raise not_found("document")
    return _doc_out(d, await _names(session, [d.device_id]))


@router.patch("/documents/{doc_id}", response_model=DocumentOut)
async def update_document(doc_id: uuid.UUID, body: DocumentUpdate, session: SessionDep):
    d = await session.get(Document, doc_id)
    if d is None:
        raise not_found("document")
    data = body.model_dump(exclude_unset=True)
    if "device_id" in data:
        await _check_device(session, data["device_id"])
    for k, v in data.items():
        setattr(d, k, v)
    await session.commit()
    await session.refresh(d)
    return _doc_out(d, await _names(session, [d.device_id]))


@router.delete("/documents/{doc_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_document(doc_id: uuid.UUID, session: SessionDep):
    d = await session.get(Document, doc_id)
    if d is None:
        raise not_found("document")
    await session.delete(d)
    await session.commit()
    return Response(status_code=status.HTTP_204_NO_CONTENT)
