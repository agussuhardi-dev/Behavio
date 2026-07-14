import { provideHttpClient, withInterceptorsFromDi } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { NgxPermissionsModule, NgxPermissionsService, NgxRolesService } from 'ngx-permissions';
import { LocalStorageService, MemoryStorageService } from '@shared/services/storage.service';
import { Menu, MenuService } from '@core/bootstrap/menu.service';
import { StartupService } from '@core/bootstrap/startup.service';

describe('StartupService', () => {
  let httpMock: HttpTestingController;
  let startup: StartupService;
  let menuService: MenuService;
  let mockPermissionsService: NgxPermissionsService;
  let mockRolesService: NgxRolesService;

  const permissions = ['canAdd', 'canDelete', 'canEdit', 'canRead'];

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [NgxPermissionsModule.forRoot()],
      providers: [
        { provide: LocalStorageService, useClass: MemoryStorageService },
        {
          provide: NgxPermissionsService,
          useValue: { loadPermissions: (_permissions: string[]) => void 0 },
        },
        {
          provide: NgxRolesService,
          useValue: { flushRoles: () => void 0, addRoles: (_params: { ADMIN: string[] }) => void 0 },
        },
        StartupService,
        provideHttpClient(withInterceptorsFromDi()),
        provideHttpClientTesting(),
      ],
    });
    httpMock = TestBed.inject(HttpTestingController);
    startup = TestBed.inject(StartupService);
    menuService = TestBed.inject(MenuService);
    mockPermissionsService = TestBed.inject(NgxPermissionsService);
    mockRolesService = TestBed.inject(NgxRolesService);
  });

  afterEach(() => httpMock.verify());

  it('memuat menu langsung dari data/menu.json tanpa login', async () => {
    const menu: Menu[] = [{ route: 'simulators', name: 'simulators', type: 'link', icon: 'apps' }];
    spyOn(menuService, 'addNamespace');
    spyOn(menuService, 'set');
    spyOn(mockPermissionsService, 'loadPermissions');
    spyOn(mockRolesService, 'flushRoles');
    spyOn(mockRolesService, 'addRoles');

    const loaded = startup.load();
    httpMock.expectOne('data/menu.json').flush({ menu });
    await loaded;

    expect(menuService.addNamespace).toHaveBeenCalledWith(menu, 'menu');
    expect(menuService.set).toHaveBeenCalledWith(menu);
    expect(mockPermissionsService.loadPermissions).toHaveBeenCalledWith(permissions);
    expect(mockRolesService.flushRoles).toHaveBeenCalledWith();
    expect(mockRolesService.addRoles).toHaveBeenCalledWith({ ADMIN: permissions });
  });

  it('menu kosong bila menu.json gagal dimuat, aplikasi tetap start', async () => {
    spyOn(menuService, 'addNamespace');
    spyOn(menuService, 'set');

    const loaded = startup.load();
    httpMock.expectOne('data/menu.json').error(new ProgressEvent('404'));
    await loaded;

    expect(menuService.addNamespace).toHaveBeenCalledWith([], 'menu');
    expect(menuService.set).toHaveBeenCalledWith([]);
  });
});
